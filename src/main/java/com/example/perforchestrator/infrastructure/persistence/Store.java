package com.example.perforchestrator.infrastructure.persistence;

import static com.example.perforchestrator.domain.Model.*;

import com.example.perforchestrator.domain.*;
import com.example.perforchestrator.infrastructure.config.Json;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class Store {

  private final JdbcTemplate db;

  public Store(final JdbcTemplate db) {
    this.db = db;
  }

  public record SubmissionRecord(String requestHash, String runId) {}

  public Optional<SubmissionRecord> submission(final String key) {
    return db
      .query(
        "SELECT request_hash,run_id FROM submissions WHERE idempotency_key=?",
        (final var row, final var index) ->
          new SubmissionRecord(row.getString(1), row.getString(2)),
        key
      )
      .stream()
      .findFirst();
  }

  public void recordSubmission(final String key, final String requestHash, final String runId) {
    db.update("INSERT INTO submissions VALUES (?,?,?)", key, requestHash, runId);
  }

  public boolean environmentAvailable(final String scope) {
    return (
      db.queryForObject(
        "SELECT COUNT(*) FROM environment_leases WHERE scope=?",
        Integer.class,
        scope
      ) == 0
    );
  }

  /** Called while the workflow coordination row is locked in the caller's transaction. */
  public void reserveEnvironment(
    final String scope,
    final String runId,
    final String owner,
    final Instant now,
    final Instant expires
  ) {
    db.update(
      "INSERT INTO environment_leases VALUES (?,?,?,?,?,?)",
      scope,
      runId,
      owner,
      1L,
      now.toString(),
      expires.toString()
    );
  }

  public boolean renewEnvironment(
    final String runId,
    final String owner,
    final Instant now,
    final Instant expires
  ) {
    return (
      db.update(
        "UPDATE environment_leases SET renewed_at=?,expires_at=?,fencing_token=fencing_token+1" +
          " WHERE run_id=? AND owner=?",
        now.toString(),
        expires.toString(),
        runId,
        owner
      ) == 1
    );
  }

  public void releaseEnvironment(final String runId) {
    db.update("DELETE FROM environment_leases WHERE run_id=?", runId);
  }

  /** Must be held inside a transaction. Serializes the local queue, worker and cancellation. */
  public void lock() {
    db.queryForObject("SELECT id FROM coordination WHERE id=1 FOR UPDATE", Integer.class);
  }

  public void plan(final Plan plan) {
    db.update("INSERT INTO plans VALUES (?,?)", plan.id(), Json.write(plan));
  }

  public Plan plan(final String id) {
    return one("SELECT body FROM plans WHERE id=?", id, Plan.class, "Plan");
  }

  public Run run(final String id) {
    return one("SELECT body FROM runs WHERE id=?", id, Run.class, "Run");
  }

  public boolean baselineStopped(final String runId) {
    return (
      db.queryForObject(
        "SELECT COUNT(*) FROM stopped_baseline_loads WHERE run_id=?",
        Integer.class,
        runId
      ) > 0
    );
  }

  public void baselineStoppedConfirmed(final String runId) {
    db.update("MERGE INTO stopped_baseline_loads (run_id) KEY(run_id) VALUES (?)", runId);
  }

  public List<AdditionalLoad> additionalLoads(final String runId) {
    return db
      .query(
        "SELECT body FROM additional_loads WHERE run_id=?",
        (final var r, final var n) -> Json.read(r.getString(1), AdditionalLoad.class),
        runId
      )
      .stream()
      .sorted(Comparator.comparing(AdditionalLoad::createdAt))
      .toList();
  }

  public AdditionalLoad additionalLoad(final String id) {
    return one(
      "SELECT body FROM additional_loads WHERE id=?",
      id,
      AdditionalLoad.class,
      "Additional load"
    );
  }

  public void insertAdditionalLoad(final AdditionalLoad load) {
    db.update(
      "INSERT INTO additional_loads VALUES (?,?,?)",
      load.id(),
      load.runId(),
      Json.write(load)
    );
  }

  public void updateAdditionalLoad(final AdditionalLoad load) {
    db.update("UPDATE additional_loads SET body=? WHERE id=?", Json.write(load), load.id());
    db.update(
      "INSERT INTO events(run_id,event_time,state,message) VALUES (?,?,?,?)",
      load.runId(),
      load.updatedAt(),
      "ADDITIONAL_LOAD",
      load.service().releaseName() + ": " + load.message()
    );
  }

  private <T> T one(final String query, final String id, final Class<T> type, final String label) {
    return db
      .query(query, (final var r, final var n) -> Json.read(r.getString(1), type), id)
      .stream()
      .findFirst()
      .orElseThrow(() -> Problem.missing(label));
  }

  public List<Run> runs() {
    return db
      .query("SELECT body FROM runs ORDER BY id", (final var r, final var n) ->
        Json.read(r.getString(1), Run.class)
      )
      .stream()
      .sorted(Comparator.comparing(Run::createdAt).reversed())
      .toList();
  }

  public void insert(final Run run) {
    db.update(
      "INSERT INTO runs VALUES (?,?,?,?,?)",
      run.id(),
      run.planId(),
      run.environment(),
      run.state().name(),
      Json.write(run)
    );
    event(run);
  }

  public void update(final Run run) {
    db.update(
      "UPDATE runs SET state=?,body=? WHERE id=?",
      run.state().name(),
      Json.write(run),
      run.id()
    );
    event(run);
  }

  public void event(final Run run) {
    db.update(
      "INSERT INTO events(run_id,event_time,state,message) VALUES (?,?,?,?)",
      run.id(),
      run.updatedAt(),
      run.state().name(),
      run.message()
    );
  }

  public List<Event> events(final String runId, final long after) {
    return db.query(
      "SELECT id,run_id,event_time,state,message FROM events WHERE run_id=? AND id>? ORDER BY id" +
        " LIMIT 500",
      (final var r, final var n) ->
        new Event(r.getLong(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5)),
      runId,
      after
    );
  }

  public void audit(final String action, final String target) {
    audit(
      com.example.perforchestrator.infrastructure.secrets.SecretServerTokens.currentActor(),
      action,
      target
    );
  }

  public void audit(final String actor, final String action, final String target) {
    db.update(
      "INSERT INTO audit_events(actor,action,target,occurred_at) VALUES" + " (?,?,?,?)",
      actor,
      action,
      target,
      Instant.now().toString()
    );
  }
}

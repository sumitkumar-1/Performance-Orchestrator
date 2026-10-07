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

  public Store(JdbcTemplate db) {
    this.db = db;
  }

  public record SubmissionRecord(String requestHash, String runId) {}

  public Optional<SubmissionRecord> submission(String key) {
    return db
        .query(
            "SELECT request_hash,run_id FROM submissions WHERE idempotency_key=?",
            (row, index) -> new SubmissionRecord(row.getString(1), row.getString(2)),
            key)
        .stream()
        .findFirst();
  }

  public void recordSubmission(String key, String requestHash, String runId) {
    db.update("INSERT INTO submissions VALUES (?,?,?)", key, requestHash, runId);
  }

  public boolean environmentAvailable(String scope) {
    return db.queryForObject(
            "SELECT COUNT(*) FROM environment_leases WHERE scope=?", Integer.class, scope)
        == 0;
  }

  /** Called while the workflow coordination row is locked in the caller's transaction. */
  public void reserveEnvironment(
      String scope, String runId, String owner, Instant now, Instant expires) {
    db.update(
        "INSERT INTO environment_leases VALUES (?,?,?,?,?,?)",
        scope,
        runId,
        owner,
        1L,
        now.toString(),
        expires.toString());
  }

  public boolean renewEnvironment(String runId, String owner, Instant now, Instant expires) {
    return db.update(
            "UPDATE environment_leases SET renewed_at=?,expires_at=?,fencing_token=fencing_token+1"
                + " WHERE run_id=? AND owner=?",
            now.toString(),
            expires.toString(),
            runId,
            owner)
        == 1;
  }

  public void releaseEnvironment(String runId) {
    db.update("DELETE FROM environment_leases WHERE run_id=?", runId);
  }

  /** Must be held inside a transaction. Serializes the local queue, worker and cancellation. */
  public void lock() {
    db.queryForObject("SELECT id FROM coordination WHERE id=1 FOR UPDATE", Integer.class);
  }

  public List<SavedProfile> profiles() {
    return db.query(
        "SELECT id,revision,body FROM profiles ORDER BY id",
        (r, n) ->
            new SavedProfile(
                r.getString(1), r.getInt(2), Json.read(r.getString(3), Profile.class)));
  }

  public SavedProfile profile(String id) {
    return db
        .query(
            "SELECT revision,body FROM profiles WHERE id=?",
            (r, n) -> new SavedProfile(id, r.getInt(1), Json.read(r.getString(2), Profile.class)),
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> Problem.missing("Profile"));
  }

  public SavedProfile save(String id, Integer expectedRevision, Profile profile) {
    int next = 1;
    String body = Json.write(profile);
    if (id == null) {
      id = UUID.randomUUID().toString();
      db.update("INSERT INTO profiles VALUES (?,?,?)", id, next, body);
    } else {
      if (expectedRevision == null)
        throw Problem.invalid("revision", "Expected revision is required");
      next = expectedRevision + 1;
      if (db.update(
              "UPDATE profiles SET revision=?,body=? WHERE id=? AND revision=?",
              next,
              body,
              id,
              expectedRevision)
          != 1) throw Problem.conflict("Profile changed or was removed. Reload before saving.");
    }
    db.update("INSERT INTO profile_revisions VALUES (?,?,?)", id, next, body);
    audit("PROFILE_SAVED", id);
    return new SavedProfile(id, next, profile);
  }

  public void plan(Plan plan) {
    db.update("INSERT INTO plans VALUES (?,?)", plan.id(), Json.write(plan));
  }

  public Plan plan(String id) {
    return one("SELECT body FROM plans WHERE id=?", id, Plan.class, "Plan");
  }

  public Run run(String id) {
    return one("SELECT body FROM runs WHERE id=?", id, Run.class, "Run");
  }

  public List<AdditionalLoad> additionalLoads(String runId) {
    return db.query("SELECT body FROM additional_loads WHERE run_id=?",(r,n)->Json.read(r.getString(1),AdditionalLoad.class),runId)
        .stream().sorted(Comparator.comparing(AdditionalLoad::createdAt)).toList();
  }

  public AdditionalLoad additionalLoad(String id) {
    return one("SELECT body FROM additional_loads WHERE id=?",id,AdditionalLoad.class,"Additional load");
  }

  public void insertAdditionalLoad(AdditionalLoad load) {
    db.update("INSERT INTO additional_loads VALUES (?,?,?)",load.id(),load.runId(),Json.write(load));
  }

  public void updateAdditionalLoad(AdditionalLoad load) {
    db.update("UPDATE additional_loads SET body=? WHERE id=?",Json.write(load),load.id());
    db.update("INSERT INTO events(run_id,event_time,state,message) VALUES (?,?,?,?)",load.runId(),load.updatedAt(),
        "ADDITIONAL_LOAD",load.service().releaseName()+": "+load.message());
  }

  private <T> T one(String query, String id, Class<T> type, String label) {
    return db.query(query, (r, n) -> Json.read(r.getString(1), type), id).stream()
        .findFirst()
        .orElseThrow(() -> Problem.missing(label));
  }

  public List<Run> runs() {
    return db
        .query("SELECT body FROM runs ORDER BY id", (r, n) -> Json.read(r.getString(1), Run.class))
        .stream()
        .sorted(Comparator.comparing(Run::createdAt).reversed())
        .toList();
  }

  public void insert(Run run) {
    db.update(
        "INSERT INTO runs VALUES (?,?,?,?,?)",
        run.id(),
        run.planId(),
        run.environment(),
        run.state().name(),
        Json.write(run));
    event(run);
  }

  public void update(Run run) {
    db.update(
        "UPDATE runs SET state=?,body=? WHERE id=?", run.state().name(), Json.write(run), run.id());
    event(run);
  }

  public void event(Run run) {
    db.update(
        "INSERT INTO events(run_id,event_time,state,message) VALUES (?,?,?,?)",
        run.id(),
        run.updatedAt(),
        run.state().name(),
        run.message());
  }

  public List<Event> events(String runId, long after) {
    return db.query(
        "SELECT id,run_id,event_time,state,message FROM events WHERE run_id=? AND id>? ORDER BY id"
            + " LIMIT 500",
        (r, n) ->
            new Event(r.getLong(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5)),
        runId,
        after);
  }

  public void audit(String action, String target) {
    audit(com.example.perforchestrator.infrastructure.secrets.SecretServerTokens.currentActor(),action,target);
  }
  public void audit(String actor, String action, String target) {
    db.update(
        "INSERT INTO audit_events(actor,action,target,occurred_at) VALUES"
            + " (?,?,?,?)",
        actor,
        action,
        target,
        Instant.now().toString());
  }
}

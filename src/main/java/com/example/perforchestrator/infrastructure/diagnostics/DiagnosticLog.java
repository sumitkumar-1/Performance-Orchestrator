package com.example.perforchestrator.infrastructure.diagnostics;

import com.example.perforchestrator.domain.Problem;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DiagnosticLog {

  private record Context(DiagnosticLog log, String id) {}

  private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

  public static <T> java.util.concurrent.Callable<T> propagate(
    final java.util.concurrent.Callable<T> work
  ) {
    final var captured = CURRENT.get();
    return () -> {
      final var previous = CURRENT.get();
      try {
        if (captured == null) CURRENT.remove();
        else CURRENT.set(captured);
        return work.call();
      } finally {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
      }
    };
  }

  private final JdbcTemplate db;
  private final boolean enabled;

  public DiagnosticLog(final JdbcTemplate db) {
    this(db, true);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public DiagnosticLog(
    final JdbcTemplate db,
    final @org.springframework.beans.factory.annotation.Value(
      "${orchestrator.diagnostics.enabled:false}"
    ) boolean enabled
  ) {
    this.db = db;
    this.enabled = enabled;
  }

  public boolean enabled() {
    return enabled;
  }

  public String create(final String parent) {
    if (!enabled) return null;
    final String id = UUID.randomUUID().toString();
    db.update(
      "INSERT INTO diagnostic_traces(id,created_at,parent_id) VALUES (?,?,?)",
      id,
      Instant.now().toString(),
      parent
    );
    return id;
  }

  public List<Map<String, Object>> recent() {
    if (!enabled) return List.of();
    return db.query(
      "SELECT id,created_at,plan_id,run_id FROM diagnostic_traces ORDER BY created_at DESC LIMIT 30",
      (final var r, final var n) -> {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", r.getString(1));
        row.put("createdAt", r.getString(2));
        row.put("planId", r.getString(3));
        row.put("runId", r.getString(4));
        return row;
      }
    );
  }

  public Scope scope(final String id) {
    if (!enabled || id == null) return new Scope(CURRENT.get(), false);
    if (
      db.queryForObject("SELECT COUNT(*) FROM diagnostic_traces WHERE id=?", Integer.class, id) == 0
    ) throw Problem.missing("Diagnostic attempt");
    final var previous = CURRENT.get();
    CURRENT.set(new Context(this, id));
    return new Scope(previous, true);
  }

  public static final class Scope implements AutoCloseable {

    private final Context previous;
    private final boolean changed;

    private Scope(final Context previous, final boolean changed) {
      this.previous = previous;
      this.changed = changed;
    }

    public void close() {
      if (changed) {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
      }
    }
  }

  public String forPlan(final String plan) {
    return lookup("plan_id", plan);
  }

  public String forRun(final String run) {
    return lookup("run_id", run);
  }

  private String lookup(final String column, final String value) {
    if (!enabled) return null;
    final var ids = db.query(
      "SELECT id FROM diagnostic_traces WHERE " + column + "=? ORDER BY created_at DESC LIMIT 1",
      (final var r, final var n) -> r.getString(1),
      value
    );
    return ids.isEmpty() ? null : ids.getFirst();
  }

  public void plan(final String trace, final String plan) {
    if (enabled) db.update("UPDATE diagnostic_traces SET plan_id=? WHERE id=?", plan, trace);
  }

  public void run(final String trace, final String run) {
    if (enabled) db.update("UPDATE diagnostic_traces SET run_id=? WHERE id=?", run, trace);
  }

  public Object operations(final String trace) {
    if (!enabled) return List.of();
    final var parents = db.query(
      "SELECT parent_id FROM diagnostic_traces WHERE id=?",
      (final var r, final var n) -> r.getString(1),
      trace
    );
    if (parents.isEmpty()) throw Problem.missing("Diagnostic attempt");
    final String parent = parents.getFirst();
    return db.query(
      "SELECT id,started_at,finished_at,kind,summary,outcome,duration_ms FROM diagnostic_operations WHERE trace_id=? OR trace_id=? ORDER BY started_at DESC,id DESC LIMIT 500",
      (final var r, final var n) -> {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", r.getString(1));
        row.put("startedAt", r.getString(2));
        row.put("finishedAt", r.getString(3));
        row.put("kind", r.getString(4));
        row.put("summary", r.getString(5));
        row.put("outcome", r.getString(6));
        row.put("durationMs", r.getObject(7));
        return row;
      },
      trace,
      parent
    );
  }

  public static Operation begin(final String kind, final String safeSummary) {
    final var context = CURRENT.get();
    if (context == null) return new Operation(null, null);
    try {
      if (
        context.log.db.queryForObject(
          "SELECT COUNT(*) FROM diagnostic_operations WHERE trace_id=?",
          Integer.class,
          context.id
        ) >= 5000
      ) return new Operation(null, null);
      final String id = UUID.randomUUID().toString();
      context.log.db.update(
        "INSERT INTO diagnostic_operations(id,trace_id,started_at,kind,summary,outcome) VALUES (?,?,?,?,?,?)",
        id,
        context.id,
        Instant.now().toString(),
        kind,
        safeSummary.substring(0, Math.min(safeSummary.length(), 2000)),
        "RUNNING"
      );
      return new Operation(context.log, id);
    } catch (final org.springframework.dao.DataAccessException ignored) {
      return new Operation(null, null);
    }
  }

  public static final class Operation {

    private final DiagnosticLog log;
    private final String id;
    private final long started = System.nanoTime();

    private Operation(final DiagnosticLog log, final String id) {
      this.log = log;
      this.id = id;
    }

    public void finish(final String safeOutcome) {
      if (log != null) try {
        log.db.update(
          "UPDATE diagnostic_operations SET finished_at=?,outcome=?,duration_ms=? WHERE id=?",
          Instant.now().toString(),
          safeOutcome,
          (System.nanoTime() - started) / 1_000_000,
          id
        );
      } catch (final org.springframework.dao.DataAccessException ignored) {}
    }
  }
}

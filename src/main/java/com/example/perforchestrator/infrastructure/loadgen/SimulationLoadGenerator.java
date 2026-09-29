package com.example.perforchestrator.infrastructure.loadgen;

import static com.example.perforchestrator.domain.Model.*;

import com.example.perforchestrator.domain.*;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "orchestrator.mode",
    havingValue = "simulation",
    matchIfMissing = true)
@Component
public class SimulationLoadGenerator implements Ports.LoadGeneratorGateway {
  private final JdbcTemplate db;

  public SimulationLoadGenerator(JdbcTemplate db) {
    this.db = db;
  }

  public String start(String runId, Plan plan) {
    String operation = "sim-load-" + runId;
    if (db.queryForObject(
            "SELECT COUNT(*) FROM simulated_loads WHERE run_id=?", Integer.class, runId)
        == 0) db.update("INSERT INTO simulated_loads VALUES (?,?,'RUNNING')", runId, operation);
    return operation;
  }

  public void stop(String runId) {
    db.update("UPDATE simulated_loads SET state='STOPPED' WHERE run_id=?", runId);
  }

  public boolean stopped(String runId) {
    return db.queryForObject(
            "SELECT COUNT(*) FROM simulated_loads WHERE run_id=? AND state='RUNNING'",
            Integer.class,
            runId)
        == 0;
  }

  public Map<String, Double> collect(String runId, Plan plan) {
    if (plan.profile().simulationCase() == SimulationCase.MISSING_METRICS) return Map.of();
    double rps = plan.profile().loadGenerator().requestsPerSecond();
    return Map.of(
        "request_count",
        rps * plan.profile().loadGenerator().measurementSeconds(),
        "throughput_rps",
        rps,
        "error_rate",
        plan.profile().simulationCase() == SimulationCase.THRESHOLD_FAILURE ? 0.1 : 0.001,
        "latency_p95_ms",
        120.0);
  }
}

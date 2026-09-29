package com.example.perforchestrator.infrastructure.ckp;

import static com.example.perforchestrator.domain.Model.*;

import com.example.perforchestrator.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "orchestrator.mode",
    havingValue = "simulation",
    matchIfMissing = true)
@Component
public class SimulationDeployment implements Ports.DeploymentGateway {
  private final JdbcTemplate db;

  public SimulationDeployment(JdbcTemplate db) {
    this.db = db;
  }

  private String scope(String cluster, String namespace, String service) {
    return cluster + "/" + namespace + "/" + service;
  }

  public String baseline(String cluster, String namespace, String service) {
    return db
        .query(
            "SELECT digest FROM simulated_deployments WHERE scope=?",
            (r, n) -> r.getString(1),
            scope(cluster, namespace, service))
        .stream()
        .findFirst()
        .orElse("not-deployed");
  }

  public void deploy(String runId, Plan plan, PreparedService service) {
    if (plan.profile().simulationCase() == SimulationCase.DEPLOYMENT_FAILURE)
      throw Problem.invalid("deployment", "Simulated deployment failure; load was not started");
    String key = scope(plan.clusterIdentity(), service.namespace(), service.serviceId());
    int changed =
        db.update(
            "UPDATE simulated_deployments SET digest=?,operation_id=? WHERE scope=?",
            service.image().digest(),
            runId + "/" + service.serviceId(),
            key);
    if (changed == 0)
      db.update(
          "INSERT INTO simulated_deployments VALUES (?,?,?)",
          key,
          service.image().digest(),
          runId + "/" + service.serviceId());
  }

  public boolean ready(Plan plan, PreparedService service) {
    return plan.profile().simulationCase() != SimulationCase.READINESS_TIMEOUT
        && service
            .image()
            .digest()
            .equals(baseline(plan.clusterIdentity(), service.namespace(), service.serviceId()));
  }
}

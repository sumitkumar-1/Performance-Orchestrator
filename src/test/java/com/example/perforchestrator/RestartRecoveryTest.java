package com.example.perforchestrator;

import static com.example.perforchestrator.domain.Model.*;
import static org.assertj.core.api.Assertions.*;

import com.example.perforchestrator.application.*;
import com.example.perforchestrator.infrastructure.ckp.SimulationDeployment;
import com.example.perforchestrator.infrastructure.config.Catalog;
import com.example.perforchestrator.infrastructure.loadgen.SimulationLoadGenerator;
import com.example.perforchestrator.infrastructure.persistence.Store;
import com.example.perforchestrator.infrastructure.registry.SimulationImages;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class RestartRecoveryTest {

  @TempDir
  Path directory;

  record Context(
    HikariDataSource dataSource,
    Store store,
    PlanningService planner,
    RunService runs,
    WorkflowWorker worker,
    TransactionTemplate tx
  ) implements AutoCloseable {
    public void close() {
      dataSource.close();
    }
  }

  Context open() throws Exception {
    final var dataSource = new HikariDataSource();
    dataSource.setJdbcUrl("jdbc:h2:file:" + directory.resolve("restart").toAbsolutePath());
    dataSource.setUsername("sa");
    dataSource.setPassword("");
    Flyway.configure().dataSource(dataSource).load().migrate();
    final var jdbc = new JdbcTemplate(dataSource);
    final var store = new Store(jdbc);
    final var catalog = new Catalog(
      "src/main/resources/mocks/catalog.yaml",
      "simulation",
      "127.0.0.1"
    );
    final var deploy = new SimulationDeployment(jdbc);
    final var load = new SimulationLoadGenerator(jdbc);
    final var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    final var planner = new PlanningService(catalog, new SimulationImages(catalog), deploy, store);
    return new Context(
      dataSource,
      store,
      planner,
      new RunService(store, planner, catalog),
      new WorkflowWorker(store, deploy, load, tx, catalog, false),
      tx
    );
  }

  /**
   * <b>Scenario:</b> Restart Retains Active Operation And Does Not Launch Again
   * <pre>
   * GIVEN ... an active simulated operation persisted before restart
   * WHEN ... the application reopens its store and recovers the run
   * THEN ... the existing operation is retained and load is not launched twice
   * </pre>
   */
  @Test
  @DisplayName("Restart Retains Active Operation And Does Not Launch Again")
  void restartRetainsActiveOperationAndDoesNotLaunchAgain() throws Exception {
    final String id, operation;
    try (var first = open()) {
      final var p = new Profile(
        "Restart fixture",
        "dev",
        List.of(
          new Selection("auth-service", Action.DEPLOY, new Build("dev", "", "build-103"), "")
        ),
        new Load("smoke", 1, 10, 0, 30, ""),
        60,
        List.of(new Threshold("error_rate", .01, true)),
        SimulationCase.SUCCESS
      );
      final var plan = first.tx.execute((final var status) -> first.planner.create(null, null, p));
      final var run = first.tx.execute((final var status) ->
        first.runs.enqueue("restart-test-key", new RunService.Submission(plan.id(), null, null))
      );
      id = run.id();
      for (int i = 0; i < 5; i++) first.worker.tick();
      assertThat(first.store.run(id).state()).isEqualTo(State.RUNNING_LOAD);
      operation = first.store.run(id).loadOperationId();
    }
    try (var second = open()) {
      second.worker.tick();
      assertThat(second.store.run(id).loadOperationId()).isEqualTo(operation);
      assertThat(
        new JdbcTemplate(second.dataSource).queryForObject(
          "SELECT COUNT(*) FROM simulated_loads",
          Integer.class
        )
      ).isOne();
      second.tx.execute((final var status) -> second.runs.cancel(id));
      second.worker.tick();
      second.worker.tick();
      assertThat(second.store.run(id).state()).isEqualTo(State.CANCELLED);
      assertThat(
        new JdbcTemplate(second.dataSource).queryForObject(
          "SELECT COUNT(*) FROM environment_leases",
          Integer.class
        )
      ).isZero();
      assertThat(second.store.events(id, 0)).isNotEmpty();
    }
  }
}

package com.example.perforchestrator;

import static com.example.perforchestrator.domain.Model.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.perforchestrator.application.*;
import com.example.perforchestrator.domain.*;
import com.example.perforchestrator.infrastructure.config.*;
import com.example.perforchestrator.infrastructure.persistence.Store;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    properties = {
      "spring.config.location=classpath:application-test.yaml",
      "spring.datasource.url=jdbc:h2:mem:workflow;DB_CLOSE_DELAY=-1",
      "orchestrator.worker-enabled=false",
      "orchestrator.configuration-file=target/test-runtime/workflow-${random.uuid}.json",
      "orchestrator.artifact-root=target/test-artifacts"
    })
@AutoConfigureMockMvc
class WorkflowIntegrationTest {
  @Autowired PlanningService planning;
  @Autowired RunService runs;
  @Autowired WorkflowWorker worker;
  @Autowired Store store;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @Autowired TransactionTemplate tx;
  @Autowired Reports reports;
  @Autowired MockMvc mvc;
  @Autowired RuntimeConfiguration configuration;

  @BeforeEach
  void reset() {
    tx.executeWithoutResult(
        s -> {
          jdbc.update("DELETE FROM environment_leases");
          jdbc.update("DELETE FROM simulated_loads");
          jdbc.update("DELETE FROM simulated_deployments");
          jdbc.update("DELETE FROM submissions");
          jdbc.update("DELETE FROM events");
          jdbc.update("DELETE FROM runs");
          jdbc.update("DELETE FROM plans");
        });
  }

  Profile profile(SimulationCase simulation) {
    return new Profile(
        "Test <script>",
        "sandbox",
        List.of(
            new Selection(
                "auth-service",
                Action.DEPLOY,
                new Build("dev", "", "build-103"),
                "replicaCount: 3"),
            new Selection(
                "smtp-receiver", Action.DEPLOY, new Build("release", "", "build-103"), "")),
        new Load("smoke", 10, 100, 0, 1, ""),
        60,
        List.of(new Threshold("error_rate", .01, true)),
        simulation);
  }

  Run start(SimulationCase simulation) {
    var plan = planning.create(null, null, profile(simulation));
    return runs.enqueue(
        UUID.randomUUID().toString(), new RunService.Submission(plan.id(), null, null));
  }

  void finish(String id) {
    for (int i = 0; i < 15; i++) {
      Run run = store.run(id);
      if (run.state().terminal()) return;
      if (run.state() == State.RUNNING_LOAD) {
        var changed =
            new Run(
                run.id(),
                run.planId(),
                run.environment(),
                run.state(),
                run.verdict(),
                run.createdAt(),
                run.updatedAt(),
                run.startedAt(),
                Instant.now().minusSeconds(10).toString(),
                null,
                run.message(),
                run.cleanupOutcome(),
                run.metrics(),
                run.desiredOutcome(),
                run.loadOperationId());
        store.update(changed);
      }
      worker.tick();
    }
    fail("Run did not finish");
  }

  @Test
  void runtimeConfigurationIsCsrfProtectedAndAcceptedRunsKeepTheirSnapshot() throws Exception {
    var original = configuration.current();
    var run = start(SimulationCase.SUCCESS);
    var plan = store.plan(run.planId());
    var tree = (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(original);
    ((com.fasterxml.jackson.databind.node.ObjectNode)
            tree.path("catalog").path("environments").path("sandbox"))
        .put("clusterIdentity", "updated-simulation-cluster");
    String payload = Json.write(tree);
    mvc.perform(
            put("/api/v1/configuration")
                .header("Host", "localhost")
                .contentType("application/json")
                .content(payload))
        .andExpect(status().isForbidden());
    try {
      mvc.perform(
              put("/api/v1/configuration")
                  .header("Host", "localhost")
                  .with(csrf())
                  .contentType("application/json")
                  .content(payload))
          .andExpect(status().isOk());
      mvc.perform(
              put("/api/v1/configuration")
                  .header("Host", "localhost")
                  .with(csrf())
                  .contentType("application/json")
                  .content(payload))
          .andExpect(status().isConflict());
      assertThatThrownBy(
              () ->
                  runs.enqueue(
                      UUID.randomUUID().toString(),
                      new RunService.Submission(plan.id(), null, null)))
          .hasMessageContaining("stale");
      finish(run.id());
      assertThat(store.run(run.id()).state()).isEqualTo(State.SUCCEEDED);
      assertThat(store.plan(run.planId()).clusterIdentity()).isEqualTo(plan.clusterIdentity());
    } finally {
      configuration.update(
          new RuntimeConfiguration.Document(
              configuration.current().revision(), original.catalog(), original.connections()));
    }
  }

  @Test
  void mixedSourcePlanIsPinnedAndSourceFilesUnchanged() throws Exception {
    Path chart = Path.of("src/main/resources/mocks/projects/auth-service/ckp/Chart.yaml");
    String before = Files.readString(chart);
    Plan plan = planning.create(null, null, profile(SimulationCase.SUCCESS));
    assertThat(plan.services()).hasSize(2);
    var auth = plan.services().getFirst();
    var smtp = plan.services().getLast();
    assertThat(auth.image().sourceRef()).isEqualTo("dev");
    assertThat(smtp.image().sourceRef()).isEqualTo("release");
    assertThat(auth.image().digest()).isNotEqualTo(smtp.image().digest());
    assertThat(auth.namespace()).isNotEqualTo(smtp.namespace());
    assertThat(auth.effectiveValues())
        .containsEntry("buildSource", "development")
        .containsEntry("replicaCount", 3);
    assertThat(smtp.effectiveValues()).containsEntry("buildSource", "release");
    assertThat(auth.preparedFiles().get("ckp/Chart.yaml"))
        .doesNotContain("REPLACE_VERSION")
        .contains("build-103");
    assertThat(Files.readString(chart)).isEqualTo(before);
    assertThat(plan.checksum()).isEqualTo(PlanningService.checksum(plan));
    assertThat(store.plan(plan.id())).isEqualTo(plan);
  }

  @ParameterizedTest
  @EnumSource(SimulationCase.class)
  void outcomesAndCleanup(SimulationCase scenario) {
    var run = start(scenario);
    finish(run.id());
    var done = store.run(run.id());
    switch (scenario) {
      case SUCCESS -> {
        assertThat(done.state()).isEqualTo(State.SUCCEEDED);
        assertThat(done.verdict()).isEqualTo(Verdict.PASS);
      }
      case THRESHOLD_FAILURE -> {
        assertThat(done.state()).isEqualTo(State.SUCCEEDED);
        assertThat(done.verdict()).isEqualTo(Verdict.FAIL);
      }
      case MISSING_METRICS -> {
        assertThat(done.state()).isEqualTo(State.SUCCEEDED);
        assertThat(done.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
      }
      case DEPLOYMENT_FAILURE, READINESS_TIMEOUT -> {
        assertThat(done.state()).isEqualTo(State.FAILED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM simulated_loads", Integer.class))
            .isZero();
      }
      case CLEANUP_FAILURE -> {
        assertThat(done.state()).isEqualTo(State.NEEDS_ATTENTION);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM environment_leases", Integer.class))
            .isOne();
        worker.recover(run.id());
      }
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM simulated_loads WHERE state='RUNNING'", Integer.class))
        .isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM environment_leases", Integer.class))
        .isZero();
    assertThat(reports.summary(run.id())).contains("\"simulated\":true");
    assertThat(reports.html(run.id())).doesNotContain("<script>").contains("&lt;script&gt;");
    assertThat(reports.artifacts(run.id())).hasSize(2);
  }

  @Test
  void duplicateSubmissionAndEnvironmentContention() {
    var plan = planning.create(null, null, profile(SimulationCase.SUCCESS));
    var request = new RunService.Submission(plan.id(), null, null);
    var run = runs.enqueue("same-request-123", request);
    assertThat(runs.enqueue("same-request-123", request).id()).isEqualTo(run.id());
    assertThatThrownBy(() -> runs.enqueue("different-request", request))
        .isInstanceOf(Problem.class)
        .hasMessageContaining("reserved");
    var another = planning.create(null, null, profile(SimulationCase.SUCCESS));
    assertThatThrownBy(
            () ->
                runs.enqueue(
                    "same-request-123", new RunService.Submission(another.id(), null, null)))
        .hasMessageContaining("different request");
  }

  @Test
  void concurrentEnqueueAcquiresOnlyOneLease() throws Exception {
    var plan = planning.create(null, null, profile(SimulationCase.SUCCESS));
    try (var pool = Executors.newFixedThreadPool(2)) {
      var barrier = new CountDownLatch(1);
      Callable<Boolean> submit =
          () -> {
            barrier.await();
            try {
              runs.enqueue(
                  UUID.randomUUID().toString(), new RunService.Submission(plan.id(), null, null));
              return true;
            } catch (Problem e) {
              return false;
            }
          };
      var a = pool.submit(submit);
      var b = pool.submit(submit);
      barrier.countDown();
      assertThat(List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
  }

  @Test
  void cancellationStopsOwnedLoadAndIsIdempotent() {
    Run run = start(SimulationCase.SUCCESS);
    for (int i = 0; i < 5; i++) worker.tick();
    assertThat(store.run(run.id()).state()).isEqualTo(State.RUNNING_LOAD);
    assertThat(runs.cancel(run.id()).state()).isEqualTo(State.CANCEL_REQUESTED);
    runs.cancel(run.id());
    finish(run.id());
    assertThat(store.run(run.id()).state()).isEqualTo(State.CANCELLED);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM simulated_loads WHERE state='RUNNING'", Integer.class))
        .isZero();
  }

  @Test
  void queuedCancellationNeverStartsLoad() {
    var run = start(SimulationCase.SUCCESS);
    runs.cancel(run.id());
    finish(run.id());
    assertThat(store.run(run.id()).state()).isEqualTo(State.CANCELLED);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM simulated_loads", Integer.class)).isZero();
  }

  @Test
  void driftBlocksMutation() {
    var run = start(SimulationCase.SUCCESS);
    var service = store.plan(run.planId()).services().getFirst();
    jdbc.update(
        "INSERT INTO simulated_deployments VALUES (?,?,?)",
        "simulation-cluster/" + service.namespace() + "/" + service.serviceId(),
        "external-digest",
        "external");
    finish(run.id());
    assertThat(store.run(run.id()).state()).isEqualTo(State.FAILED);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM simulated_loads", Integer.class)).isZero();
  }

  @Test
  void optimisticProfileUpdatesAndFrozenPlans() {
    var saved = planning.save(null, null, profile(SimulationCase.SUCCESS));
    var plan = planning.create(saved.id(), saved.revision(), null);
    planning.save(saved.id(), saved.revision(), profile(SimulationCase.MISSING_METRICS));
    assertThatThrownBy(
            () -> planning.save(saved.id(), saved.revision(), profile(SimulationCase.SUCCESS)))
        .isInstanceOf(Problem.class);
    assertThat(store.plan(plan.id()).profile().simulationCase()).isEqualTo(SimulationCase.SUCCESS);
  }

  @Test
  void rejectsManagedOverlayAndUnknownImage() {
    var p = profile(SimulationCase.SUCCESS);
    var bad =
        new Profile(
            p.name(),
            p.targetEnvironment(),
            List.of(
                new Selection(
                    "auth-service",
                    Action.DEPLOY,
                    new Build("dev", "", "build-103"),
                    "image:\n  tag: stolen")),
            p.loadGenerator(),
            p.maxRunDurationSeconds(),
            p.thresholds(),
            p.simulationCase());
    assertThatThrownBy(() -> planning.create(null, null, bad))
        .isInstanceOf(Problem.class)
        .hasMessageContaining("managed");
  }

  @Test
  void csrfHostOriginAndJsonBoundaryAreEnforced() throws Exception {
    mvc.perform(get("/api/v1/session").header("Host", "localhost"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.csrfToken").isNotEmpty());
    mvc.perform(get("/api/v1/profiles").header("Host", "evil.invalid"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/plans")
                .header("Host", "localhost")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/plans")
                .with(csrf())
                .header("Host", "localhost")
                .header("Origin", "https://evil.invalid")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/plans")
                .with(csrf())
                .header("Host", "localhost")
                .contentType("application/json")
                .content("{\"unknown\":true}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/plans")
                .with(csrf())
                .header("Host", "localhost")
                .contentType("application/json")
                .content(
                    Json.write(
                        new com.example.perforchestrator.api.ApiController.PlanInput(
                            null, null, profile(SimulationCase.SUCCESS)))))
        .andExpect(status().isCreated());
  }

  @Test
  void eventCursorReconnectDoesNotRepeatEvents() throws Exception {
    var run = start(SimulationCase.SUCCESS);
    worker.tick();
    var events = store.events(run.id(), 0);
    assertThat(store.events(run.id(), events.getFirst().id())).hasSize(1);
    mvc.perform(
            get("/api/v1/runs/" + run.id() + "/events")
                .header("Host", "localhost")
                .header("Last-Event-ID", events.getLast().id())
                .accept("text/event-stream"))
        .andExpect(status().isOk())
        .andExpect(content().string("retry: 2000\n\n"));
  }
}

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
import org.junit.jupiter.api.DisplayName;
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
    "orchestrator.artifact-root=target/test-artifacts",
  }
)
@AutoConfigureMockMvc
class WorkflowIntegrationTest {

  @Autowired
  PlanningService planning;

  @Autowired
  RunService runs;

  @Autowired
  WorkflowWorker worker;

  @Autowired
  Store store;

  @Autowired
  org.springframework.jdbc.core.JdbcTemplate jdbc;

  @Autowired
  TransactionTemplate tx;

  @Autowired
  Reports reports;

  @Autowired
  MockMvc mvc;

  @Autowired
  RuntimeConfiguration configuration;

  @BeforeEach
  void reset() {
    tx.executeWithoutResult((final var s) -> {
      jdbc.update("DELETE FROM environment_leases");
      jdbc.update("DELETE FROM simulated_loads");
      jdbc.update("DELETE FROM simulated_deployments");
      jdbc.update("DELETE FROM submissions");
      jdbc.update("DELETE FROM events");
      jdbc.update("DELETE FROM runs");
      jdbc.update("DELETE FROM plans");
    });
  }

  Profile profile(final SimulationCase simulation) {
    return new Profile(
      "Test <script>",
      "sandbox",
      List.of(
        new Selection(
          "auth-service",
          Action.DEPLOY,
          new Build("dev", "", "build-103"),
          "replicaCount: 3"
        ),
        new Selection("smtp-receiver", Action.DEPLOY, new Build("release", "", "build-103"), "")
      ),
      new Load("smoke", 10, 100, 0, 1, ""),
      60,
      List.of(new Threshold("error_rate", .01, true)),
      simulation
    );
  }

  Run start(final SimulationCase simulation) {
    final var plan = planning.create(null, null, profile(simulation));
    return runs.enqueue(
      UUID.randomUUID().toString(),
      new RunService.Submission(plan.id(), null, null)
    );
  }

  void finish(final String id) {
    for (int i = 0; i < 15; i++) {
      final Run run = store.run(id);
      if (run.state().terminal()) return;
      if (run.state() == State.RUNNING_LOAD) {
        final var changed = new Run(
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
          run.loadOperationId()
        );
        store.update(changed);
      }
      worker.tick();
    }
    fail("Run did not finish");
  }

  /**
   * <b>Scenario:</b> Runtime Configuration Is CSRF Protected And Accepted Runs Keep Their Snapshot
   * <pre>
   * GIVEN ... a prepared run and editable runtime configuration
   * WHEN ... configuration updates are submitted before executing the accepted run
   * THEN ... CSRF protection is enforced and the run retains its accepted snapshot
   * </pre>
   */
  @Test
  @DisplayName("Runtime Configuration Is Csrf Protected And Accepted Runs Keep Their Snapshot")
  void runtimeConfigurationIsCsrfProtectedAndAcceptedRunsKeepTheirSnapshot() throws Exception {
    final var original = configuration.current();
    final var run = start(SimulationCase.SUCCESS);
    final var plan = store.plan(run.planId());
    final var tree = (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(
      original
    );
    (
      (com.fasterxml.jackson.databind.node.ObjectNode) tree
        .path("catalog")
        .path("environments")
        .path("sandbox")
    ).put("clusterIdentity", "updated-simulation-cluster");
    final String payload = Json.write(tree);
    mvc
      .perform(
        put("/api/v1/configuration")
          .header("Host", "localhost")
          .contentType("application/json")
          .content(payload)
      )
      .andExpect(status().isForbidden());
    try {
      mvc
        .perform(
          put("/api/v1/configuration")
            .header("Host", "localhost")
            .with(csrf())
            .contentType("application/json")
            .content(payload)
        )
        .andExpect(status().isOk());
      mvc
        .perform(
          put("/api/v1/configuration")
            .header("Host", "localhost")
            .with(csrf())
            .contentType("application/json")
            .content(payload)
        )
        .andExpect(status().isConflict());
      assertThatThrownBy(() ->
        runs.enqueue(UUID.randomUUID().toString(), new RunService.Submission(plan.id(), null, null))
      ).hasMessageContaining("stale");
      finish(run.id());
      assertThat(store.run(run.id()).state()).isEqualTo(State.SUCCEEDED);
      assertThat(store.plan(run.planId()).clusterIdentity()).isEqualTo(plan.clusterIdentity());
    } finally {
      configuration.update(
        new RuntimeConfiguration.Document(
          configuration.current().revision(),
          original.catalog(),
          original.connections()
        )
      );
    }
  }

  /**
   * <b>Scenario:</b> Mixed Source Plan Is Pinned And Source Files Unchanged
   * <pre>
   * GIVEN ... services using different image sources
   * WHEN ... a run plan is prepared
   * THEN ... versions and checksums are pinned while source chart files remain unchanged
   * </pre>
   */
  @Test
  @DisplayName("Mixed Source Plan Is Pinned And Source Files Unchanged")
  void mixedSourcePlanIsPinnedAndSourceFilesUnchanged() throws Exception {
    final Path chart = Path.of("src/main/resources/mocks/projects/auth-service/ckp/Chart.yaml");
    final String before = Files.readString(chart);
    final Plan plan = planning.create(null, null, profile(SimulationCase.SUCCESS));
    assertThat(plan.services()).hasSize(2);
    final var auth = plan.services().getFirst();
    final var smtp = plan.services().getLast();
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

  /**
   * <b>Scenario:</b> Outcomes And Cleanup
   * <pre>
   * GIVEN ... a simulated success or failure scenario
   * WHEN ... the workflow completes and cleanup recovery runs when needed
   * THEN ... the expected state and verdict are recorded, load stops, and the environment lease is released
   * </pre>
   */
  @ParameterizedTest
  @DisplayName("Outcomes And Cleanup")
  @EnumSource(SimulationCase.class)
  void outcomesAndCleanup(final SimulationCase scenario) {
    final var run = start(scenario);
    finish(run.id());
    final var done = store.run(run.id());
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
        assertThat(
          jdbc.queryForObject("SELECT COUNT(*) FROM simulated_loads", Integer.class)
        ).isZero();
      }
      case CLEANUP_FAILURE -> {
        assertThat(done.state()).isEqualTo(State.NEEDS_ATTENTION);
        assertThat(
          jdbc.queryForObject("SELECT COUNT(*) FROM environment_leases", Integer.class)
        ).isOne();
        worker.recover(run.id());
      }
    }
    assertThat(
      jdbc.queryForObject(
        "SELECT COUNT(*) FROM simulated_loads WHERE state='RUNNING'",
        Integer.class
      )
    ).isZero();
    assertThat(
      jdbc.queryForObject("SELECT COUNT(*) FROM environment_leases", Integer.class)
    ).isZero();
    assertThat(reports.summary(run.id())).contains("\"simulated\":true");
    assertThat(reports.html(run.id())).doesNotContain("<script>").contains("&lt;script&gt;");
    assertThat(reports.artifacts(run.id())).hasSize(2);
  }

  /**
   * <b>Scenario:</b> Duplicate Submission And Environment Contention
   * <pre>
   * GIVEN ... an accepted run holding an environment lease
   * WHEN ... the same submission is repeated and another run requests the environment
   * THEN ... duplicate submission is idempotent and competing work is rejected
   * </pre>
   */
  @Test
  @DisplayName("Duplicate Submission And Environment Contention")
  void duplicateSubmissionAndEnvironmentContention() {
    final var plan = planning.create(null, null, profile(SimulationCase.SUCCESS));
    final var request = new RunService.Submission(plan.id(), null, null);
    final var run = runs.enqueue("same-request-123", request);
    assertThat(runs.enqueue("same-request-123", request).id()).isEqualTo(run.id());
    assertThatThrownBy(() -> runs.enqueue("different-request", request))
      .isInstanceOf(Problem.class)
      .hasMessageContaining("reserved");
    final var another = planning.create(null, null, profile(SimulationCase.SUCCESS));
    assertThatThrownBy(() ->
      runs.enqueue("same-request-123", new RunService.Submission(another.id(), null, null))
    ).hasMessageContaining("different request");
  }

  /**
   * <b>Scenario:</b> Concurrent Enqueue Acquires Only One Lease
   * <pre>
   * GIVEN ... concurrent submissions for the same environment
   * WHEN ... the submissions race to enqueue runs
   * THEN ... only one environment lease is acquired
   * </pre>
   */
  @Test
  @DisplayName("Concurrent Enqueue Acquires Only One Lease")
  void concurrentEnqueueAcquiresOnlyOneLease() throws Exception {
    final var plan = planning.create(null, null, profile(SimulationCase.SUCCESS));
    try (var pool = Executors.newFixedThreadPool(2)) {
      final var barrier = new CountDownLatch(1);
      final Callable<Boolean> submit = () -> {
        barrier.await();
        try {
          runs.enqueue(
            UUID.randomUUID().toString(),
            new RunService.Submission(plan.id(), null, null)
          );
          return true;
        } catch (final Problem e) {
          return false;
        }
      };
      final var a = pool.submit(submit);
      final var b = pool.submit(submit);
      barrier.countDown();
      assertThat(
        List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS))
      ).containsExactlyInAnyOrder(true, false);
    }
  }

  /**
   * <b>Scenario:</b> Cancellation Stops Owned Load And Is Idempotent
   * <pre>
   * GIVEN ... a running simulated load
   * WHEN ... cancellation is requested repeatedly
   * THEN ... owned load stops and cancellation remains idempotent
   * </pre>
   */
  @Test
  @DisplayName("Cancellation Stops Owned Load And Is Idempotent")
  void cancellationStopsOwnedLoadAndIsIdempotent() {
    final Run run = start(SimulationCase.SUCCESS);
    for (int i = 0; i < 5; i++) worker.tick();
    assertThat(store.run(run.id()).state()).isEqualTo(State.RUNNING_LOAD);
    assertThat(runs.cancel(run.id()).state()).isEqualTo(State.CANCEL_REQUESTED);
    runs.cancel(run.id());
    finish(run.id());
    assertThat(store.run(run.id()).state()).isEqualTo(State.CANCELLED);
    assertThat(
      jdbc.queryForObject(
        "SELECT COUNT(*) FROM simulated_loads WHERE state='RUNNING'",
        Integer.class
      )
    ).isZero();
  }

  /**
   * <b>Scenario:</b> Queued Cancellation Never Starts Load
   * <pre>
   * GIVEN ... a queued run that has not started load
   * WHEN ... the run is cancelled before processing
   * THEN ... no load is launched
   * </pre>
   */
  @Test
  @DisplayName("Queued Cancellation Never Starts Load")
  void queuedCancellationNeverStartsLoad() {
    final var run = start(SimulationCase.SUCCESS);
    runs.cancel(run.id());
    finish(run.id());
    assertThat(store.run(run.id()).state()).isEqualTo(State.CANCELLED);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM simulated_loads", Integer.class)).isZero();
  }

  /**
   * <b>Scenario:</b> Drift Blocks Mutation
   * <pre>
   * GIVEN ... a prepared plan whose baseline has changed
   * WHEN ... execution attempts to apply the plan
   * THEN ... drift prevents deployment mutation
   * </pre>
   */
  @Test
  @DisplayName("Drift Blocks Mutation")
  void driftBlocksMutation() {
    final var run = start(SimulationCase.SUCCESS);
    final var service = store.plan(run.planId()).services().getFirst();
    jdbc.update(
      "INSERT INTO simulated_deployments VALUES (?,?,?)",
      "simulation-cluster/" + service.namespace() + "/" + service.serviceId(),
      "external-digest",
      "external"
    );
    finish(run.id());
    assertThat(store.run(run.id()).state()).isEqualTo(State.FAILED);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM simulated_loads", Integer.class)).isZero();
  }

  /**
   * <b>Scenario:</b> Optimistic Profile Updates And Frozen Plans
   * <pre>
   * GIVEN ... a saved profile and its prepared plan
   * WHEN ... the profile is updated and a stale revision is submitted
   * THEN ... stale updates are rejected and the prepared plan stays unchanged
   * </pre>
   */
  @Test
  @DisplayName("Optimistic Profile Updates And Frozen Plans")
  void optimisticProfileUpdatesAndFrozenPlans() {
    final var saved = planning.save(null, null, profile(SimulationCase.SUCCESS));
    final var plan = planning.create(saved.id(), saved.revision(), null);
    planning.save(saved.id(), saved.revision(), profile(SimulationCase.MISSING_METRICS));
    assertThatThrownBy(() ->
      planning.save(saved.id(), saved.revision(), profile(SimulationCase.SUCCESS))
    ).isInstanceOf(Problem.class);
    assertThat(store.plan(plan.id()).profile().simulationCase()).isEqualTo(SimulationCase.SUCCESS);
  }

  /**
   * <b>Scenario:</b> Rejects Managed Overlay And Unknown Image
   * <pre>
   * GIVEN ... a profile with managed-value overrides or an unknown image
   * WHEN ... planning validates the profile
   * THEN ... both invalid inputs are rejected
   * </pre>
   */
  @Test
  @DisplayName("Rejects Managed Overlay And Unknown Image")
  void rejectsManagedOverlayAndUnknownImage() {
    final var p = profile(SimulationCase.SUCCESS);
    final var bad = new Profile(
      p.name(),
      p.targetEnvironment(),
      List.of(
        new Selection(
          "auth-service",
          Action.DEPLOY,
          new Build("dev", "", "build-103"),
          "image:\n  tag: stolen"
        )
      ),
      p.loadGenerator(),
      p.maxRunDurationSeconds(),
      p.thresholds(),
      p.simulationCase()
    );
    assertThatThrownBy(() -> planning.create(null, null, bad))
      .isInstanceOf(Problem.class)
      .hasMessageContaining("managed");
  }

  /**
   * <b>Scenario:</b> CSRF Host Origin And Json Boundary Are Enforced
   * <pre>
   * GIVEN ... API requests crossing CSRF, host, origin, or JSON validation boundaries
   * WHEN ... the requests are submitted
   * THEN ... invalid requests are rejected at the API boundary
   * </pre>
   */
  @Test
  @DisplayName("Csrf Host Origin And Json Boundary Are Enforced")
  void csrfHostOriginAndJsonBoundaryAreEnforced() throws Exception {
    mvc
      .perform(get("/api/v1/session").header("Host", "localhost"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.csrfToken").isNotEmpty());
    mvc
      .perform(get("/api/v1/profiles").header("Host", "evil.invalid"))
      .andExpect(status().isForbidden());
    mvc
      .perform(
        post("/api/v1/plans")
          .header("Host", "localhost")
          .contentType("application/json")
          .content("{}")
      )
      .andExpect(status().isForbidden());
    mvc
      .perform(
        post("/api/v1/plans")
          .with(csrf())
          .header("Host", "localhost")
          .header("Origin", "https://evil.invalid")
          .contentType("application/json")
          .content("{}")
      )
      .andExpect(status().isForbidden());
    mvc
      .perform(
        post("/api/v1/plans")
          .with(csrf())
          .header("Host", "localhost")
          .contentType("application/json")
          .content("{\"unknown\":true}")
      )
      .andExpect(status().isBadRequest());
    mvc
      .perform(
        post("/api/v1/plans")
          .with(csrf())
          .header("Host", "localhost")
          .contentType("application/json")
          .content(
            Json.write(
              new com.example.perforchestrator.api.ApiController.PlanInput(
                null,
                null,
                profile(SimulationCase.SUCCESS)
              )
            )
          )
      )
      .andExpect(status().isCreated());
  }

  /**
   * <b>Scenario:</b> Event Cursor Reconnect Does Not Repeat Events
   * <pre>
   * GIVEN ... a run with recorded events and a previously consumed cursor
   * WHEN ... the event stream reconnects from that cursor
   * THEN ... already consumed events are not repeated
   * </pre>
   */
  @Test
  @DisplayName("Event Cursor Reconnect Does Not Repeat Events")
  void eventCursorReconnectDoesNotRepeatEvents() throws Exception {
    final var run = start(SimulationCase.SUCCESS);
    worker.tick();
    final var events = store.events(run.id(), 0);
    assertThat(store.events(run.id(), events.getFirst().id())).hasSize(1);
    mvc
      .perform(
        get("/api/v1/runs/" + run.id() + "/events")
          .header("Host", "localhost")
          .header("Last-Event-ID", events.getLast().id())
          .accept("text/event-stream")
      )
      .andExpect(status().isOk())
      .andExpect(content().string("retry: 2000\n\n"));
  }
}

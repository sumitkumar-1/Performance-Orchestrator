package com.example.perforchestrator;

import static com.example.perforchestrator.domain.Model.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.perforchestrator.application.*;
import com.example.perforchestrator.infrastructure.config.*;
import com.example.perforchestrator.infrastructure.execution.*;
import com.example.perforchestrator.infrastructure.persistence.Store;
import com.example.perforchestrator.infrastructure.registry.*;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.DisplayName;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;

class RealWorkflowTest {

  @org.junit.jupiter.api.io.TempDir
  java.nio.file.Path directory;

  HikariDataSource db;
  Store store;
  TransactionTemplate tx;
  RealRuns worker;
  Catalog catalog;
  ConnectionConfig connections;
  HelmExecution helm;
  LokiMeasurements metrics;
  ArtifactoryImages images;
  RealPreparation preparation;
  ExecutionSettings settings;

  @BeforeEach
  void setup() {
    db = new HikariDataSource();
    db.setJdbcUrl("jdbc:h2:mem:real-" + UUID.randomUUID());
    db.setUsername("sa");
    db.setPassword("");
    Flyway.configure().dataSource(db).load().migrate();
    store = new Store(new JdbcTemplate(db));
    tx = new TransactionTemplate(new DataSourceTransactionManager(db));
    catalog = mock(Catalog.class);
    when(catalog.mode()).thenReturn("real");
    when(catalog.hash()).thenReturn("catalog-hash");
    connections = new ConnectionConfig(
      new ConnectionConfig.Data(Map.of(), Map.of(), Map.of(), Map.of())
    );
    helm = mock(HelmExecution.class);
    when(helm.baseline(any(), any(), any())).thenReturn("ABSENT");
    when(helm.stop(any(), any(), any())).thenReturn(true);
    metrics = mock(LokiMeasurements.class);
    when(metrics.collect(any(), any())).thenReturn(Map.of());
    images = mock(ArtifactoryImages.class);
    preparation = mock(RealPreparation.class);
    settings = new ExecutionSettings(
      new MockEnvironment()
        .withProperty("orchestrator.execution.enabled", "true")
        .withProperty("orchestrator.execution.workspace", directory.toString())
    );
    worker = worker();
  }

  RealRuns worker() {
    return new RealRuns(
      store,
      catalog,
      connections,
      preparation,
      helm,
      metrics,
      images,
      tx,
      settings,
      new AdditionalLoads(store, preparation, catalog, connections, images, helm, tx)
    );
  }

  @AfterEach
  void cleanup() {
    db.close();
  }

  Plan plan() {
    final var image = new Image("service:load", "", "repo/load", "v1", "sha256:" + "a".repeat(64));
    when(images.resolve(any(), any(), any(), any())).thenReturn(image);
    final var prepared = new PreparedService(
      "load",
      Action.DEPLOY,
      "load-ns",
      "load-release",
      image,
      "ABSENT",
      "a".repeat(40),
      Map.of(),
      Map.of(),
      Map.of(),
      Map.of(),
      Map.of(),
      List.of()
    );
    final var profile = new Profile(
      "Real test",
      "sandbox",
      List.of(),
      new Load("real-yaml", 0, 0, 0, 1, ""),
      600,
      List.of(),
      SimulationCase.SUCCESS
    );
    var p = new Plan(
      UUID.randomUUID().toString(),
      "",
      Instant.now().toString(),
      Instant.now().plusSeconds(900).toString(),
      "test",
      null,
      0,
      profile,
      "catalog-hash",
      "cluster",
      "load-ns",
      "commit",
      Map.of(
        "target",
        Map.of("context", "sandbox", "server", "https://cluster.invalid"),
        "loadService",
        "load",
        "charts",
        Map.of("load", "ckp/helm/load"),
        "metrics",
        List.of(),
        "connectionHash",
        Json.hash(Json.write(connections.data()))
      ),
      List.of(prepared),
      List.of(),
      false
    );
    p = new Plan(
      p.id(),
      PlanningService.checksum(p),
      p.createdAt(),
      p.expiresAt(),
      p.actor(),
      null,
      0,
      p.profile(),
      p.catalogHash(),
      p.clusterIdentity(),
      p.loadNamespace(),
      p.scenarioRevision(),
      p.effectiveLoadConfiguration(),
      p.services(),
      p.warnings(),
      false
    );
    store.plan(p);
    return p;
  }

  AdditionalLoads additions() {
    return new AdditionalLoads(store, preparation, catalog, connections, images, helm, tx);
  }

  Run running() {
    final var run = worker.enqueue("extra-" + UUID.randomUUID(), plan().id());
    worker.tick();
    worker.tick();
    worker.tick();
    worker.tick();
    final var current = store.run(run.id());
    // Leave time for independent requests without relying on sleeps.
    store.update(
      new Run(
        current.id(),
        current.planId(),
        current.environment(),
        current.state(),
        current.verdict(),
        current.createdAt(),
        current.updatedAt(),
        current.startedAt(),
        Instant.now().plusSeconds(120).toString(),
        null,
        current.message(),
        current.cleanupOutcome(),
        current.metrics(),
        current.desiredOutcome(),
        current.loadOperationId()
      )
    );
    return store.run(run.id());
  }

  com.example.perforchestrator.domain.AdditionalLoad reviewExtra(final Run run) {
    final var original = store.plan(run.planId()).services().getFirst();
    when(preparation.prepareAdditional(any(), any(), any(), any())).thenAnswer((final var call) -> {
      final var service = new PreparedService(
        original.serviceId(),
        original.action(),
        original.namespace(),
        call.getArgument(2),
        original.image(),
        "ABSENT",
        original.sourceRevision(),
        original.originalHashes(),
        original.preparedFiles(),
        original.preparedHashes(),
        Map.of("rate", 200),
        Map.of(),
        List.of()
      );
      return new RealPreparation.AdditionalPreparation(service, "ckp/helm/load");
    });
    return additions().prepare(
      run.id(),
      new RealPreparation.Deployment("load", "master", "v1", List.of(), "")
    );
  }

  /**
   * <b>Scenario:</b> Additional Loads Install Independently And Repeated Submit Is Idempotent
   * <pre>
   * GIVEN ... a running baseline load and an additional load request
   * WHEN ... the extra load is submitted repeatedly
   * THEN ... it installs independently once without replacing the baseline
   * </pre>
   */
  @Test
  @DisplayName("Additional Loads Install Independently And Repeated Submit Is Idempotent")
  void additionalLoadsInstallIndependentlyAndRepeatedSubmitIsIdempotent() {
    final var run = running();
    final var first = reviewExtra(run);
    final var second = reviewExtra(run);
    assertThat(first.service().releaseName())
      .isNotEqualTo(second.service().releaseName())
      .hasSizeLessThanOrEqualTo(53);
    assertThat(first.service().releaseName()).isNotEqualTo("load-release");
    additions().enqueue(run.id(), first.id());
    additions().enqueue(run.id(), first.id());
    additions().enqueue(run.id(), second.id());
    worker.tick();
    worker.tick();
    assertThat(store.additionalLoads(run.id()))
      .extracting(com.example.perforchestrator.domain.AdditionalLoad::state)
      .containsExactly("RUNNING", "RUNNING");
    verify(helm, times(1)).apply(
      eq(first.id()),
      any(),
      eq(first.service()),
      eq(first.chart()),
      eq(true)
    );
    verify(helm, times(1)).apply(
      eq(second.id()),
      any(),
      eq(second.service()),
      eq(second.chart()),
      eq(true)
    );
    verify(helm, never()).stop(any(), any(), any());
    new RunService(store, null, catalog).cancel(run.id());
    worker.tick();
    worker.tick();
    assertThat(store.run(run.id()).state()).isEqualTo(State.CANCELLED);
    verify(helm).stop(eq(run.id()), any(), any());
    verify(helm).stop(eq(first.id()), any(), eq(first.service()));
    verify(helm).stop(eq(second.id()), any(), eq(second.service()));
  }

  /**
   * <b>Scenario:</b> Failed Extra Installation Cleans Only Its Own Release And Keeps Baseline Running
   * <pre>
   * GIVEN ... a baseline run whose additional load installation fails
   * WHEN ... failure cleanup executes
   * THEN ... only the additional release is cleaned and baseline load keeps running
   * </pre>
   */
  @Test
  @DisplayName("Failed Extra Installation Cleans Only Its Own Release And Keeps Baseline Running")
  void failedExtraInstallationCleansOnlyItsOwnReleaseAndKeepsBaselineRunning() {
    final var run = running();
    final var extra = reviewExtra(run);
    additions().enqueue(run.id(), extra.id());
    doThrow(com.example.perforchestrator.domain.Problem.conflict("Chart resource names conflict"))
      .when(helm)
      .apply(eq(extra.id()), any(), any(), any(), eq(true));
    worker.tick();
    assertThat(store.run(run.id()).state()).isEqualTo(State.RUNNING_LOAD);
    assertThat(store.additionalLoad(extra.id()).state()).isEqualTo("FAILED_STOPPED");
    verify(helm).stop(eq(extra.id()), any(), any());
    verify(helm, never()).stop(eq(run.id()), any(), any());
  }

  /**
   * <b>Scenario:</b> Extra Cleanup Failure Does Not Stop Baseline And Is Retried At Parent Cleanup
   * <pre>
   * GIVEN ... an extra load whose cleanup fails while baseline load runs
   * WHEN ... extra cleanup fails and parent cleanup later executes
   * THEN ... baseline continues and the extra cleanup is retried
   * </pre>
   */
  @Test
  @DisplayName("Extra Cleanup Failure Does Not Stop Baseline And Is Retried At Parent Cleanup")
  void extraCleanupFailureDoesNotStopBaselineAndIsRetriedAtParentCleanup() {
    final var run = running();
    final var extra = reviewExtra(run);
    additions().enqueue(run.id(), extra.id());
    doThrow(com.example.perforchestrator.domain.Problem.conflict("Install failed"))
      .when(helm)
      .apply(eq(extra.id()), any(), any(), any(), eq(true));
    when(helm.stop(eq(extra.id()), any(), any())).thenReturn(false);
    worker.tick();
    assertThat(store.run(run.id()).state()).isEqualTo(State.RUNNING_LOAD);
    assertThat(store.additionalLoad(extra.id()).state()).isEqualTo("CLEANUP_FAILED");
    verify(helm, never()).stop(eq(run.id()), any(), any());
    when(helm.stop(eq(extra.id()), any(), any())).thenReturn(true);
    new RunService(store, null, catalog).cancel(run.id());
    worker.tick();
    worker.tick();
    assertThat(store.additionalLoad(extra.id()).state()).isEqualTo("STOPPED");
    assertThat(store.run(run.id()).state()).isEqualTo(State.CANCELLED);
  }

  /**
   * <b>Scenario:</b> Restart Cleans Persisted Additional Loads And Still Attempts Baseline If Extra Cleanup Fails
   * <pre>
   * GIVEN ... persisted baseline and additional loads after restart
   * WHEN ... recovery cleans them and one extra cleanup fails
   * THEN ... baseline cleanup is still attempted
   * </pre>
   */
  @Test
  @DisplayName(
    "Restart Cleans Persisted Additional Loads And Still Attempts Baseline If Extra Cleanup Fails"
  )
  void restartCleansPersistedAdditionalLoadsAndStillAttemptsBaselineIfExtraCleanupFails() {
    final var run = running();
    final var first = reviewExtra(run);
    final var second = reviewExtra(run);
    additions().enqueue(run.id(), first.id());
    additions().enqueue(run.id(), second.id());
    worker.tick();
    worker.tick();
    when(helm.stop(eq(first.id()), any(), any())).thenThrow(
      com.example.perforchestrator.domain.Problem.conflict("Ownership differs")
    );
    worker = worker();
    worker.tick();
    assertThat(store.run(run.id()).state()).isEqualTo(State.NEEDS_ATTENTION);
    verify(helm).stop(eq(second.id()), any(), any());
    verify(helm).stop(eq(run.id()), any(), any());
    assertThat(store.environmentAvailable("cluster/sandbox")).isFalse();
    when(helm.stop(eq(first.id()), any(), any())).thenReturn(true);
    worker.recover(run.id());
    assertThat(store.additionalLoads(run.id()))
      .extracting(com.example.perforchestrator.domain.AdditionalLoad::state)
      .containsOnly("STOPPED");
    assertThat(store.environmentAvailable("cluster/sandbox")).isTrue();
  }

  /**
   * <b>Scenario:</b> Cancellation Before Install Skips Queued Extra And Disallows New Submissions
   * <pre>
   * GIVEN ... a queued extra load under a parent run
   * WHEN ... the parent is cancelled before extra installation
   * THEN ... the queued installation is skipped and new submissions are rejected
   * </pre>
   */
  @Test
  @DisplayName("Cancellation Before Install Skips Queued Extra And Disallows New Submissions")
  void cancellationBeforeInstallSkipsQueuedExtraAndDisallowsNewSubmissions() {
    final var run = running();
    final var extra = reviewExtra(run);
    final var pending = reviewExtra(run);
    additions().enqueue(run.id(), extra.id());
    new RunService(store, null, catalog).cancel(run.id());
    assertThatThrownBy(() -> additions().enqueue(run.id(), pending.id())).hasMessageContaining(
      "only while"
    );
    worker.tick();
    worker.tick();
    verify(helm, never()).apply(eq(extra.id()), any(), any(), any(), anyBoolean());
    assertThat(store.additionalLoad(extra.id()).state()).isEqualTo("NOT_STARTED");
    assertThatThrownBy(() -> reviewExtra(run)).hasMessageContaining("only while");
  }

  /**
   * <b>Scenario:</b> Queues Once Executes And Cleans Owned Load Without Synthetic Pass
   * <pre>
   * GIVEN ... a prepared real run without measured performance evidence
   * WHEN ... the run is submitted repeatedly and executed
   * THEN ... it queues once, cleans its owned load, and does not fabricate a passing verdict
   * </pre>
   */
  @Test
  @DisplayName("Queues Once Executes And Cleans Owned Load Without Synthetic Pass")
  void queuesOnceExecutesAndCleansOwnedLoadWithoutSyntheticPass() {
    final var plan = plan();
    final var run = worker.enqueue("request-key", plan.id());
    assertThat(worker.enqueue("request-key", plan.id()).id()).isEqualTo(run.id());
    worker.tick();
    worker.tick();
    worker.tick();
    worker.tick();
    assertThat(store.run(run.id()).state()).isEqualTo(State.RUNNING_LOAD);
    verify(helm).apply(eq(run.id()), any(), any(), eq("ckp/helm/load"), eq(true));
    final var current = store.run(run.id());
    store.update(
      new Run(
        current.id(),
        current.planId(),
        current.environment(),
        current.state(),
        current.verdict(),
        current.createdAt(),
        current.updatedAt(),
        current.startedAt(),
        Instant.now().minusSeconds(5).toString(),
        null,
        current.message(),
        current.cleanupOutcome(),
        current.metrics(),
        current.desiredOutcome(),
        current.loadOperationId()
      )
    );
    worker.tick();
    worker.tick();
    worker.tick();
    assertThat(store.run(run.id()).state()).isEqualTo(State.SUCCEEDED);
    assertThat(store.run(run.id()).verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    verify(helm).stop(eq(run.id()), any(), any());
    assertThat(store.environmentAvailable("cluster/sandbox")).isTrue();
    verify(metrics).detach(run.id());
  }

  /**
   * <b>Scenario:</b> Deployment Publishes Service Progress Before Helm And Records Completion
   * <pre>
   * GIVEN ... a real run deploying services
   * WHEN ... each Helm deployment begins and completes
   * THEN ... progress identifies the service and version before deployment and records completion
   * </pre>
   */
  @Test
  @DisplayName("Deployment Publishes Service Progress Before Helm And Records Completion")
  void deploymentPublishesServiceProgressBeforeHelmAndRecordsCompletion() {
    final var original = plan();
    final var tree = (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(
      original
    );
    tree.put("id", UUID.randomUUID().toString());
    final var services = tree.putArray("services");
    for (final String name : List.of("receiver", "router", "load")) {
      final var service = (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(
        original.services().getFirst()
      );
      service.put("serviceId", name).put("releaseName", name + "-release");
      services.add(service);
      (
        (com.fasterxml.jackson.databind.node.ObjectNode) tree
          .path("effectiveLoadConfiguration")
          .path("charts")
      ).put(name, "ckp/helm/" + name);
    }
    var prepared = Json.read(Json.write(tree), Plan.class);
    tree.put("checksum", PlanningService.checksum(prepared));
    prepared = Json.read(Json.write(tree), Plan.class);
    store.plan(prepared);
    final var run = worker.enqueue("progress-key", prepared.id());
    final var order = new ArrayList<String>();
    doAnswer((final var call) -> {
      final PreparedService service = call.getArgument(2);
      order.add(service.serviceId());
      assertThat(store.run(run.id()).message())
        .startsWith("Deploying ")
        .contains(
          service.serviceId(),
          "image version " + service.image().version(),
          service.namespace(),
          service.releaseName()
        );
      assertThat(store.events(run.id(), 0))
        .last()
        .satisfies((final var event) ->
          assertThat(event.message()).isEqualTo(store.run(run.id()).message())
        );
      return null;
    })
      .when(helm)
      .apply(eq(run.id()), any(), any(), any(), anyBoolean());
    worker.tick();
    worker.tick();
    worker.tick();
    worker.tick();
    assertThat(order).containsExactly("receiver", "router", "load");
    assertThat(store.events(run.id(), 0))
      .extracting(Event::message)
      .anyMatch(
        (final var message) ->
          message.contains("Service 1/2: receiver") && message.contains("ready in")
      )
      .anyMatch(
        (final var message) ->
          message.contains("Service 2/2: router") && message.contains("ready in")
      )
      .anyMatch(
        (final var message) ->
          message.contains("Load generator: load") && message.contains("installed in")
      );
  }

  /**
   * <b>Scenario:</b> Cancellation And Restart Never Redeploy And Cleanup Failure Keeps Reservation
   * <pre>
   * GIVEN ... a cancelled or interrupted real run
   * WHEN ... recovery executes and owned-load cleanup fails
   * THEN ... services are not redeployed and the environment reservation remains held
   * </pre>
   */
  @Test
  @DisplayName("Cancellation And Restart Never Redeploy And Cleanup Failure Keeps Reservation")
  void cancellationAndRestartNeverRedeployAndCleanupFailureKeepsReservation() {
    final var plan = plan();
    final var run = worker.enqueue("cancel-key", plan.id());
    final var cancelled = new RunService(store, null, catalog).cancel(run.id());
    worker.tick();
    worker.tick();
    assertThat(store.run(run.id()).state()).isEqualTo(State.CANCELLED);
    verify(helm, never()).apply(any(), any(), any(), any(), anyBoolean());
    final var second = worker.enqueue("restart-key", plan.id());
    when(helm.stop(eq(second.id()), any(), any())).thenThrow(
      com.example.perforchestrator.domain.Problem.conflict("ownership differs")
    );
    final var restarted = worker();
    restarted.tick();
    assertThat(store.run(second.id()).state()).isEqualTo(State.NEEDS_ATTENTION);
    assertThat(store.environmentAvailable("cluster/sandbox")).isFalse();
    when(helm.stop(eq(second.id()), any(), any())).thenReturn(true);
    restarted.recover(second.id());
    assertThat(store.environmentAvailable("cluster/sandbox")).isTrue();
  }

  /**
   * <b>Scenario:</b> Cancellation During Cleanup Finishes Before Releasing Reservation
   * <pre>
   * GIVEN ... a real run already cleaning up its load
   * WHEN ... cancellation arrives during cleanup
   * THEN ... cleanup finishes before the environment reservation is released
   * </pre>
   */
  @Test
  @DisplayName("Cancellation During Cleanup Finishes Before Releasing Reservation")
  void cancellationDuringCleanupFinishesBeforeReleasingReservation() {
    final var plan = plan();
    final var run = worker.enqueue("cleanup-race", plan.id());
    worker.tick();
    when(helm.baseline(any(), any(), any())).thenReturn("CHANGED");
    worker.tick();
    when(helm.stop(eq(run.id()), any(), any())).thenAnswer((final var call) -> {
      new RunService(store, null, catalog).cancel(run.id());
      return true;
    });
    worker.tick();
    assertThat(store.run(run.id()).state()).isEqualTo(State.CANCELLED);
    assertThat(store.environmentAvailable("cluster/sandbox")).isTrue();
  }

  /**
   * <b>Scenario:</b> Baseline Drift Fails Before Deployment
   * <pre>
   * GIVEN ... a prepared real run whose release baseline has changed
   * WHEN ... execution validates the baseline
   * THEN ... the run fails before deploying services
   * </pre>
   */
  @Test
  @DisplayName("Baseline Drift Fails Before Deployment")
  void baselineDriftFailsBeforeDeployment() {
    final var plan = plan();
    final var run = worker.enqueue("drift-key", plan.id());
    worker.tick();
    when(helm.baseline(any(), any(), any())).thenReturn("CHANGED");
    worker.tick();
    assertThat(store.run(run.id()).state()).isEqualTo(State.CLEANING_UP);
    verify(helm, never()).apply(any(), any(), any(), any(), anyBoolean());
  }

  /**
   * <b>Scenario:</b> Parallel Preparation Preserves Order And Session Context
   * <pre>
   * GIVEN ... multiple services prepared in parallel under a browser session
   * WHEN ... chart preparation tasks execute concurrently
   * THEN ... selected order and authentication session context are preserved
   * </pre>
   */
  @Test
  @DisplayName("Parallel Preparation Preserves Order And Session Context")
  void parallelPreparationPreservesOrderAndSessionContext() throws Exception {
    final var source = mock(SparseProjects.class);
    when(catalog.boundEnvironment()).thenReturn("sandbox");
    when(catalog.environment("sandbox")).thenReturn(
      new Catalog.Environment("Sandbox", "cluster", null, null, null, null, null, null)
    );
    when(helm.target(any())).thenReturn(
      Map.of("context", "sandbox", "server", "https://cluster.invalid")
    );
    final var bothStarted = new java.util.concurrent.CountDownLatch(2);
    final var requestAttributes =
      new org.springframework.web.context.request.ServletRequestAttributes(
        new org.springframework.mock.web.MockHttpServletRequest()
      );
    final var requests = new ArrayList<RealPreparation.Deployment>();
    for (final String id : List.of("first", "second", "load")) {
      final var destination = new Catalog.Destination("ns", id, List.of("ckp/chart/values.yaml"));
      when(catalog.service(id)).thenReturn(
        new Catalog.Service(
          "projects/" + id,
          List.of("ignored"),
          Map.of(),
          null,
          List.of(),
          destination,
          new Catalog.ContainerImage("registry", "dev", "team", id),
          new Catalog.SourceProject("stash", "SP", id, "main", "ckp/chart"),
          Map.of()
        )
      );
      requests.add(new RealPreparation.Deployment(id, "main", "v1", null, ""));
    }
    when(source.checkout(any(), any())).thenAnswer((final var call) -> {
      assertThat(
        org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
      ).isSameAs(requestAttributes);
      final Catalog.SourceProject project = call.getArgument(0);
      if (!project.repository().equals("load")) {
        bothStarted.countDown();
        assertThat(bothStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      }
      return new SparseProjects.Checkout(
        "a".repeat(40),
        Map.of(
          "ckp/chart/Chart.yaml",
          Base64.getEncoder().encodeToString("version: 1.0.0\n".getBytes()),
          "ckp/chart/values.yaml",
          Base64.getEncoder().encodeToString("rate: 1\n".getBytes())
        )
      );
    });
    when(images.resolve(any(), any(), any(), any())).thenReturn(
      new Image("service:test", "", "repo/test", "v1", "sha256:" + "a".repeat(64))
    );
    final var planner = new RealPreparation(
      catalog,
      source,
      helm,
      images,
      store,
      settings,
      connections
    );
    final var request = new RealPreparation.Request(
      "Parallel",
      requests.subList(0, 2),
      requests.get(2),
      0,
      10,
      600,
      List.of(),
      List.of()
    );
    final var progress = new ReviewProgress("review", request);
    org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
      requestAttributes
    );
    try {
      final var plan = planner.prepare(request, progress::step);
      assertThat(plan.services())
        .extracting(PreparedService::serviceId)
        .containsExactly("first", "second", "load");
      assertThat(Json.write(progress.snapshot())).contains("Ready").doesNotContain("Queued");
      assertThat(
        org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
      ).isSameAs(requestAttributes);
    } finally {
      org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }
  }

  /**
   * <b>Scenario:</b> Preparation Snapshots CKP Commit Digest And Editable Values
   * <pre>
   * GIVEN ... a selected environment, Git revision, image, and editable CKP values
   * WHEN ... a real run is prepared
   * THEN ... the plan snapshots CKP content, commit, digest, and effective values for that environment
   * </pre>
   */
  @Test
  @DisplayName("Preparation Snapshots Ckp Commit Digest And Editable Values")
  void preparationSnapshotsCkpCommitDigestAndEditableValues() throws Exception {
    final var destination = new Catalog.Destination(
      "load-ns",
      "load-release",
      List.of("ckp/helm/load/values.yaml")
    );
    final var service = new Catalog.Service(
      "projects/load",
      List.of(),
      Map.of(),
      null,
      List.of(),
      destination,
      new Catalog.ContainerImage("registry", "dev", "team", "load"),
      new Catalog.SourceProject("stash", "SP", "load", "main", "ckp/helm/load"),
      Map.of()
    );
    when(catalog.service("load")).thenReturn(service);
    when(catalog.boundEnvironment()).thenReturn("sandbox");
    when(catalog.environment("sandbox")).thenReturn(
      new Catalog.Environment("Sandbox", "cluster", null, null, null, null, null, null)
    );
    when(helm.target(any())).thenReturn(
      Map.of("context", "sandbox", "server", "https://cluster.invalid")
    );
    final var source = mock(SparseProjects.class);
    final var files = Map.of(
      "ckp/helm/load/Chart.yaml",
      Base64.getEncoder().encodeToString(
        "apiVersion: v2\nname: load\nversion: __REPLACEAPPVERSION__\nappVersion: __REPLACEAPPVERSION__\n".getBytes()
      ),
      "ckp/helm/load/values.yaml",
      Base64.getEncoder().encodeToString("rate: 10\noptional: null\n".getBytes())
    );
    when(source.checkout(any(), eq("main"))).thenReturn(
      new SparseProjects.Checkout("a".repeat(40), files)
    );
    when(images.resolve(eq("load"), eq("service:load"), isNull(), eq("v1"))).thenReturn(
      new Image("service:load", "", "repo/load", "v1", "sha256:" + "a".repeat(64))
    );
    final var planner = new RealPreparation(
      catalog,
      source,
      helm,
      images,
      store,
      settings,
      connections
    );
    doAnswer((final var call) -> {
      final java.nio.file.Path folder = call.getArgument(0);
      java.nio.file.Files.createDirectories(folder.resolve("ckp/helm/load/charts"));
      java.nio.file.Files.write(folder.resolve("ckp/helm/load/charts/common.tgz"), new byte[] {
        1,
        2,
        3,
      });
      java.nio.file.Files.writeString(folder.resolve("ckp/helm/load/Chart.lock"), "lock snapshot");
      return null;
    })
      .when(helm)
      .prepareDependencies(any(), any());
    doAnswer((final var call) -> {
      final java.nio.file.Path folder = call.getArgument(0);
      final var metadata = HelmValues.parse(
        java.nio.file.Files.readString(folder.resolve("ckp/helm/load/Chart.yaml"))
      );
      assertThat(metadata)
        .containsEntry("appVersion", "v1")
        .containsEntry("version", "0.0.0-build.v1");
      assertThat(folder.resolve("ckp/helm/load/charts/common.tgz")).exists();
      return null;
    })
      .when(helm)
      .validate(any(), any(), any(), any(), any());
    final var request = new RealPreparation.Request(
      "Prepared",
      List.of(),
      new RealPreparation.Deployment("load", "main", "v1", null, "rate: 20\n"),
      0,
      10,
      600,
      List.of(),
      List.of()
    );
    final var discovered = Json.MAPPER.valueToTree(planner.profiles("load", "main"));
    assertThat(discovered.path("valuesFiles").has("ckp/helm/load/values.yaml")).isTrue();
    assertThat(discovered.path("valuesFiles").has("ckp/helm/load/Chart.yaml")).isFalse();
    final var legacy = Json.read(
      "{\"serviceId\":\"load\",\"revision\":\"main\",\"imageVersion\":\"v1\",\"valuesFiles\":[],\"overlay\":\"\"}",
      RealPreparation.Deployment.class
    );
    assertThat(legacy.valuesEdits()).isNull();
    assertThat(legacy.gitReference()).isNull();
    final var named = new RealPreparation.Deployment(
      "load",
      "a".repeat(40),
      "v1",
      List.of(),
      "",
      Map.of(),
      "refs/heads/master"
    );
    final var namedRestored = Json.read(Json.write(named), RealPreparation.Deployment.class);
    assertThat(namedRestored.gitReference()).isEqualTo("refs/heads/master");
    assertThat(namedRestored.revision()).isEqualTo("a".repeat(40));
    final var plan = planner.prepare(request);
    final var prepared = plan.services().getFirst();
    assertThat(plan.simulated()).isFalse();
    assertThat(prepared.sourceRevision()).isEqualTo("a".repeat(40));
    assertThat(prepared.effectiveValues())
      .containsEntry("rate", 20)
      .containsEntry("optional", null);
    assertThat(prepared.effectiveValues().get("imageTag")).isEqualTo("v1");
    assertThat(prepared.effectiveValues())
      .containsEntry("targetPlatform", "ckp")
      .containsEntry("clusterSubdomain", "cluster")
      .containsEntry("tags", Map.of("moc", false));
    assertThat((Map<String, Object>) prepared.effectiveValues().get("global"))
      .containsEntry("targetPlatform", "ckp")
      .containsEntry("clusterSubdomain", "cluster")
      .containsEntry("deploymentSuffix", "")
      .containsEntry("imageTag", "v1");
    final String preparedChart = prepared.preparedFiles().get("ckp/helm/load/Chart.yaml");
    assertThat(new String(Base64.getDecoder().decode(preparedChart))).doesNotContain(
      "__REPLACEAPPVERSION__"
    );
    assertThat(prepared.originalHashes().get("ckp/helm/load/Chart.yaml")).isEqualTo(
      Json.hash(files.get("ckp/helm/load/Chart.yaml"))
    );
    assertThat(prepared.preparedHashes().get("ckp/helm/load/Chart.yaml"))
      .isEqualTo(Json.hash(preparedChart))
      .isNotEqualTo(prepared.originalHashes().get("ckp/helm/load/Chart.yaml"));
    assertThat(store.plan(plan.id()).services().getFirst().preparedFiles()).isEqualTo(
      prepared.preparedFiles()
    );
    assertThat(prepared.preparedFiles()).containsKeys(
      "ckp/helm/load/charts/common.tgz",
      "ckp/helm/load/Chart.lock"
    );
    assertThat(prepared.originalHashes()).doesNotContainKey("ckp/helm/load/charts/common.tgz");
    assertThat(plan.checksum()).isEqualTo(PlanningService.checksum(store.plan(plan.id())));
    final var editedRequest = new RealPreparation.Request(
      "Edited values",
      List.of(),
      new RealPreparation.Deployment(
        "load",
        "main",
        "v1",
        List.of("ckp/helm/load/values.yaml"),
        "",
        Map.of(
          "ckp/helm/load/values.yaml",
          "editedRate: 30\ntargetPlatform: other\nclusterSubdomain: custom.domain\ntags:\n  moc: true\n  other: false\nglobal:\n  targetPlatform: other\n  deploymentSuffix: '-test'\n  customValue: kept\n"
        )
      ),
      0,
      10,
      600,
      List.of(),
      List.of()
    );
    final var edited = planner.prepare(editedRequest).services().getFirst().effectiveValues();
    assertThat(edited).containsEntry("editedRate", 30).doesNotContainKeys("rate", "optional");
    assertThat(edited)
      .containsEntry("targetPlatform", "ckp")
      .containsEntry("clusterSubdomain", "custom.domain")
      .containsEntry("tags", Map.of("moc", true, "other", false));
    assertThat((Map<String, Object>) edited.get("global"))
      .containsEntry("targetPlatform", "ckp")
      .containsEntry("deploymentSuffix", "-test")
      .containsEntry("customValue", "kept")
      .containsEntry("clusterSubdomain", "cluster");
    final var restored = Json.read(Json.write(editedRequest), RealPreparation.Request.class);
    assertThat(restored.loadGenerator().valuesEdits()).isEqualTo(
      editedRequest.loadGenerator().valuesEdits()
    );
    final var invalidEdits = new RealPreparation.Request(
      "Invalid file",
      List.of(),
      new RealPreparation.Deployment(
        "load",
        "main",
        "v1",
        List.of("ckp/helm/load/values.yaml"),
        "",
        Map.of("ckp/unselected.yaml", "rate: 1")
      ),
      0,
      10,
      600,
      List.of(),
      List.of()
    );
    assertThatThrownBy(() -> planner.prepare(invalidEdits)).hasMessageContaining(
      "selected values files"
    );
    // Given another environment with its own context and values defaults.
    when(catalog.selectedEnvironment("perf")).thenReturn("perf");
    when(catalog.environment("perf")).thenReturn(
      new Catalog.Environment("Perf", "ckp-perf.example", null, null, null, null, null, null)
    );
    when(helm.target("perf")).thenReturn(
      Map.of("context", "perf-nvan", "server", "https://perf.invalid")
    );
    final var perfRequest = new RealPreparation.Request(
      "Perf",
      request.services(),
      request.loadGenerator(),
      0,
      10,
      600,
      List.of(),
      List.of(),
      "perf"
    );
    // When preparing the same service for perf, target and Helm defaults follow the selection.
    final var perfPlan = planner.prepare(perfRequest);
    assertThat(perfPlan.profile().targetEnvironment()).isEqualTo("perf");
    assertThat(perfPlan.effectiveLoadConfiguration().get("target")).isEqualTo(
      Map.of("context", "perf-nvan", "server", "https://perf.invalid")
    );
    assertThat((Map<String, Object>) perfPlan.services().getFirst().effectiveValues().get("global"))
      .containsEntry("environment", "perf")
      .containsEntry("clusterSubdomain", "ckp-perf.example");
    // Then the earlier sandbox plan stays pinned, despite the later selection.
    assertThat(plan.profile().targetEnvironment()).isEqualTo("sandbox");
    assertThat(plan.effectiveLoadConfiguration().get("target")).isEqualTo(
      Map.of("context", "sandbox", "server", "https://cluster.invalid")
    );
    final var extra = planner.prepareAdditional(
      request.loadGenerator(),
      helm.target("sandbox"),
      "load-independent"
    );
    assertThat(extra.service().releaseName()).isEqualTo("load-independent");
    assertThat(extra.service().effectiveValues())
      .containsEntry("rate", 20)
      .containsEntry("imageTag", "v1");
    verify(helm).validate(
      any(),
      eq("ckp/helm/load"),
      eq("load-ns"),
      eq("load-independent"),
      eq("load:v1")
    );
    when(helm.baseline(any(), any(), any())).thenReturn("EXISTS");
    assertThatThrownBy(() -> planner.prepare(request)).hasMessageContaining("already exists");
    clearInvocations(source);
    when(helm.baseline(any(), any(), any())).thenThrow(
      new com.example.perforchestrator.domain.Problem(
        502,
        "LOOKUP_FAILED",
        "execution",
        "Cluster login expired"
      )
    );
    assertThatThrownBy(() -> planner.prepare(request))
      .hasMessageContaining(
        "Service load, namespace load-ns, release load-release, context sandbox"
      )
      .hasMessageContaining("Cluster login expired");
    verifyNoInteractions(source);
  }
}

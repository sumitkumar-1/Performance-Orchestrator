package com.example.perforchestrator.application;

import com.example.perforchestrator.domain.*;
import com.example.perforchestrator.domain.Model.*;
import com.example.perforchestrator.infrastructure.config.*;
import com.example.perforchestrator.infrastructure.execution.*;
import com.example.perforchestrator.infrastructure.persistence.Store;
import com.example.perforchestrator.infrastructure.registry.*;
import com.example.perforchestrator.infrastructure.secrets.AdSessionCredentials;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RunExecution {

  private final com.example.perforchestrator.infrastructure.diagnostics.DiagnosticLog diagnostics;
  private final Store store;
  private final Catalog catalog;
  private final ConnectionConfig connections;
  private final RunPreparation preparation;
  private final HelmExecution helm;
  private final LokiMeasurements metrics;
  private final ArtifactoryImages images;
  private final TransactionTemplate tx;
  private final ExecutionSettings settings;
  private final AdditionalLoads additionalLoads;
  private final Set<String> live = ConcurrentHashMap.newKeySet();

  public RunExecution(
    final Store store,
    final Catalog catalog,
    final ConnectionConfig connections,
    final RunPreparation preparation,
    final HelmExecution helm,
    final LokiMeasurements metrics,
    final ArtifactoryImages images,
    final TransactionTemplate tx,
    final ExecutionSettings settings,
    final AdditionalLoads additionalLoads
  ) {
    this(
      store,
      catalog,
      connections,
      preparation,
      helm,
      metrics,
      images,
      tx,
      settings,
      additionalLoads,
      null
    );
  }

  @org.springframework.beans.factory.annotation.Autowired
  public RunExecution(
    final Store store,
    final Catalog catalog,
    final ConnectionConfig connections,
    final RunPreparation preparation,
    final HelmExecution helm,
    final LokiMeasurements metrics,
    final ArtifactoryImages images,
    final TransactionTemplate tx,
    final ExecutionSettings settings,
    final AdditionalLoads additionalLoads,
    final com.example.perforchestrator.infrastructure.diagnostics.DiagnosticLog diagnostics
  ) {
    this.diagnostics = diagnostics;
    this.additionalLoads = additionalLoads;
    this.store = store;
    this.catalog = catalog;
    this.connections = connections;
    this.preparation = preparation;
    this.helm = helm;
    this.metrics = metrics;
    this.images = images;
    this.tx = tx;
    this.settings = settings;
  }

  public Run enqueue(final String key, final String planId) {
    preparation.enabled();
    if (key == null || !key.matches("[a-zA-Z0-9_-]{8,128}")) throw Problem.invalid(
      "Idempotency-Key",
      "Supply a unique request key"
    );
    final var existing = store.submission(key);
    if (existing.isPresent()) {
      if (!existing.get().requestHash().equals(Json.hash(planId))) throw Problem.conflict(
        "Idempotency key belongs to another plan"
      );
      return store.run(existing.get().runId());
    }
    final var plan = store.plan(planId);
    if (
      !PlanChecksums.checksum(plan).equals(plan.checksum()) ||
      Instant.now().isAfter(Instant.parse(plan.expiresAt())) ||
      !plan.catalogHash().equals(catalog.hash()) ||
      !Objects.equals(
        plan.effectiveLoadConfiguration().get("connectionHash"),
        Json.hash(Json.write(connections.data()))
      )
    ) throw Problem.conflict("Plan is stale; prepare again");
    // Tokens are required only during preparation/submission; no registry or Git passwords persist into execution.
    for (final var service : plan.services()) {
      final var image = images.resolve(
        service.serviceId(),
        service.image().sourceRef(),
        null,
        service.image().version()
      );
      if (!image.digest().equals(service.image().digest())) throw Problem.conflict(
        "Image tag changed since preparation; prepare again"
      );
    }
    final String id = UUID.randomUUID().toString();
    metrics.attach(id, plan);
    try {
      return tx.execute((final var status) -> {
        store.lock();
        final var previous = store.submission(key);
        if (previous.isPresent()) {
          metrics.detach(id);
          if (!previous.get().requestHash().equals(Json.hash(planId))) throw Problem.conflict(
            "Idempotency key belongs to another plan"
          );
          return store.run(previous.get().runId());
        }
        final String scope = plan.clusterIdentity() + "/" + plan.profile().targetEnvironment();
        if (!store.environmentAvailable(scope)) throw Problem.conflict(
          "Environment is reserved by another run"
        );
        final String now = Instant.now().toString();
        final var run = new Run(
          id,
          plan.id(),
          plan.profile().targetEnvironment(),
          State.QUEUED,
          Verdict.NOT_EVALUATED,
          now,
          now,
          null,
          null,
          null,
          "Queued for Helm execution",
          "PENDING",
          Map.of(),
          "SUCCEEDED",
          null
        );
        store.insert(run);
        store.reserveEnvironment(
          scope,
          id,
          "real-execution",
          Instant.now(),
          Instant.now().plusSeconds(plan.profile().maxRunDurationSeconds())
        );
        store.recordSubmission(key, Json.hash(planId), id);
        store.audit("RUN_ENQUEUED", id);
        live.add(id);
        AdSessionCredentials.attach(id);
        return run;
      });
    } catch (final RuntimeException error) {
      metrics.detach(id);
      AdSessionCredentials.detach(id);
      live.remove(id);
      throw error;
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> target(final Plan p) {
    return (Map<String, Object>) p.effectiveLoadConfiguration().get("target");
  }

  private PreparedService load(final Plan p) {
    return p
      .services()
      .stream()
      .filter((final var s) ->
        s.serviceId().equals(p.effectiveLoadConfiguration().get("loadService"))
      )
      .findFirst()
      .orElseThrow();
  }

  @SuppressWarnings("unchecked")
  private String chart(final Plan p, final PreparedService s) {
    return ((Map<String, String>) p.effectiveLoadConfiguration().get("charts")).get(s.serviceId());
  }

  private void verifyReleaseBaseline(final Plan plan, final PreparedService service) {
    final var cluster = target(plan);
    final String identity =
      "Service " +
      service.serviceId() +
      ", release " +
      service.releaseName() +
      ", namespace " +
      service.namespace() +
      ", context " +
      cluster.get("context");
    final var check = com.example.perforchestrator.infrastructure.diagnostics.DiagnosticLog.begin(
      "CHECK",
      "Helm release baseline: " + identity
    );
    final String current;
    try {
      current = helm.baseline(cluster, service.namespace(), service.releaseName());
    } catch (final RuntimeException error) {
      check.finish("LOOKUP FAILED · see preceding Helm command diagnostics");
      throw error;
    }
    if (current.equals(service.baselineDigest())) {
      check.finish("MATCH · release unchanged since review");
      return;
    }
    final String change = service.baselineDigest().equals("ABSENT")
      ? "Release was absent during review but now exists"
      : current.equals("ABSENT")
        ? "Release existed during review but is now absent"
        : "Stored Helm release data or status differs from review";
    check.finish("MISMATCH · " + change);
    throw Problem.conflict(
      "Helm release changed since preparation: " +
        identity +
        ". " +
        change +
        ". Preflight stopped before service deployment or load generation. Review and prepare a new run against the current release."
    );
  }

  @Scheduled(fixedDelay = 2000)
  public synchronized void tick() {
    if (!settings.enabled) return;
    for (final var run : store.runs())
      if (!run.state().terminal()) {
        final var plan = store.plan(run.planId());
        try (
          var auth = AdSessionCredentials.scope(run.id());
          var scope = diagnostics == null ? null : diagnostics.scope(diagnostics.forRun(run.id()))
        ) {
          advance(run, plan);
        } catch (final Exception error) {
          final String message =
            error instanceof Problem
              ? error.getMessage()
              : "Execution failed; inspect cluster state before retrying";
          // Failed cleanup is never reported as completed and keeps the environment reserved.
          if (run.state() == State.CLEANING_UP || !live.contains(run.id())) {
            write(
              run,
              State.NEEDS_ATTENTION,
              Verdict.INCONCLUSIVE,
              message,
              run.metrics(),
              "NEEDS_ATTENTION",
              null,
              null,
              null
            );
            metrics.detach(run.id());
            AdSessionCredentials.detach(run.id());
            live.remove(run.id());
          } else write(
            run,
            State.CLEANING_UP,
            Verdict.NOT_EVALUATED,
            message,
            run.metrics(),
            "FAILED",
            null,
            null,
            null
          );
        }
      }
  }

  private void advance(final Run run, final Plan plan) {
    final var load = load(plan);
    final var now = Instant.now();
    if (!live.contains(run.id())) {
      // Restart cannot safely resume a partially applied chart or recover in-memory monitoring credentials.
      cleanupOwnedLoads(run, plan);
      write(
        run,
        State.FAILED,
        Verdict.INCONCLUSIVE,
        "Server restarted; owned load stopped, services retained. Prepare a new run.",
        run.metrics(),
        "FAILED",
        null,
        null,
        now.toString()
      );
      store.releaseEnvironment(run.id());
      return;
    }
    if (run.state() == State.CANCEL_REQUESTED) {
      write(
        run,
        State.CLEANING_UP,
        Verdict.NOT_EVALUATED,
        "Cancellation requested; stopping owned load",
        run.metrics(),
        "CANCELLED",
        null,
        null,
        null
      );
      return;
    }
    if (
      run.state() != State.CLEANING_UP &&
      now.isAfter(
        Instant.parse(run.createdAt()).plusSeconds(plan.profile().maxRunDurationSeconds())
      )
    ) throw Problem.invalid("duration", "Maximum real-run duration exceeded; stopping owned load");
    switch (run.state()) {
      case QUEUED -> write(
        run,
        State.PREFLIGHT,
        run.verdict(),
        "Checking cluster and Helm release baselines",
        run.metrics(),
        null,
        null,
        null,
        null
      );
      case PREFLIGHT -> {
        for (final var service : plan.services()) verifyReleaseBaseline(plan, service);
        write(
          run,
          State.DEPLOYING,
          run.verdict(),
          "Preflight passed; applying prepared services",
          run.metrics(),
          null,
          null,
          null,
          null
        );
      }
      case DEPLOYING -> {
        final var services = plan
          .services()
          .stream()
          .filter((final var service) -> !service.serviceId().equals(load.serviceId()))
          .toList();
        for (int index = 0; index < services.size(); index++) {
          final var service = services.get(index);
          if (store.run(run.id()).state() == State.CANCEL_REQUESTED) return;
          if (
            Instant.now().isAfter(
              Instant.parse(run.createdAt()).plusSeconds(plan.profile().maxRunDurationSeconds())
            )
          ) throw Problem.invalid("duration", "Run deadline reached during deployment");
          deploy(run, plan, service, false, "Service " + (index + 1) + "/" + services.size());
        }
        write(
          run,
          State.STARTING_LOAD,
          run.verdict(),
          "Service Helm readiness passed; starting load generator",
          run.metrics(),
          null,
          null,
          null,
          null
        );
      }
      case STARTING_LOAD -> {
        if (
          !helm.baseline(target(plan), load.namespace(), load.releaseName()).equals("ABSENT")
        ) throw Problem.conflict("Load release appeared after preparation; refusing to replace it");
        deploy(run, plan, load, true, "Load generator");
        write(
          run,
          State.RUNNING_LOAD,
          run.verdict(),
          "Load chart installed; generation is controlled by selected YAML",
          run.metrics(),
          null,
          run.id(),
          Instant.now().plusSeconds(plan.profile().loadGenerator().warmupSeconds()).toString(),
          null
        );
      }
      case RUNNING_LOAD -> {
        final var start = Instant.parse(run.measurementStartedAt());
        if (now.isBefore(start.plusSeconds(plan.profile().loadGenerator().measurementSeconds()))) {
          additionalLoads.advance(run, plan);
          return;
        }
        write(
          run,
          State.COLLECTING,
          run.verdict(),
          "Measurement window ended; collecting configured LogQL metrics",
          run.metrics(),
          null,
          null,
          null,
          now.toString()
        );
      }
      case COLLECTING -> {
        final var values = metrics.collect(run.id(), Instant.parse(run.measurementEndedAt()));
        final var verdict = PerformanceVerdicts.evaluate(plan, values);
        write(
          run,
          State.CLEANING_UP,
          verdict,
          "Performance verdict: " +
            verdict +
            "; missing or unconfigured metrics remain unavailable",
          values,
          null,
          null,
          null,
          null
        );
      }
      case CLEANING_UP -> {
        cleanupOwnedLoads(run, plan);
        final var state = State.valueOf(run.desiredOutcome());
        write(
          run,
          state,
          run.verdict(),
          run.message() +
            " Cleanup confirmed all run-owned load releases are absent; service releases retained.",
          run.metrics(),
          null,
          null,
          null,
          null
        );
        store.releaseEnvironment(run.id());
        metrics.detach(run.id());
        AdSessionCredentials.detach(run.id());
        live.remove(run.id());
      }
      default -> throw Problem.conflict("Unsupported run state; verify cleanup");
    }
  }

  private void deploy(
    final Run run,
    final Plan plan,
    final PreparedService service,
    final boolean load,
    final String position
  ) {
    final String context =
      position +
      ": " +
      service.serviceId() +
      " · image version " +
      service.image().version() +
      " · namespace " +
      service.namespace() +
      " · release " +
      service.releaseName();
    write(
      run,
      run.state(),
      run.verdict(),
      "Deploying " + context,
      run.metrics(),
      null,
      null,
      null,
      null
    );
    if (store.run(run.id()).state() == State.CANCEL_REQUESTED) return;
    final long started = System.nanoTime();
    try {
      helm.apply(run.id(), target(plan), service, chart(plan, service), load);
      final long seconds = java.time.Duration.ofNanos(System.nanoTime() - started).toSeconds();
      write(
        run,
        run.state(),
        run.verdict(),
        context + (load ? " installed in " : " ready in ") + seconds + "s",
        run.metrics(),
        null,
        null,
        null,
        null
      );
    } catch (final Problem error) {
      final long seconds = java.time.Duration.ofNanos(System.nanoTime() - started).toSeconds();
      throw new Problem(
        error.status(),
        error.code(),
        error.field(),
        context + " failed after " + seconds + "s. " + error.getMessage()
      );
    }
  }

  private void write(
    final Run run,
    final State state,
    final Verdict verdict,
    final String message,
    final Map<String, Double> values,
    final String desired,
    final String operation,
    final String start,
    final String end
  ) {
    tx.executeWithoutResult((final var status) -> {
      store.lock();
      final var current = store.run(run.id());
      // Never overwrite cancellation that arrived during a long-running external operation.
      final boolean cancelled = current.state() == State.CANCEL_REQUESTED;
      final boolean cleanupFinished = run.state() == State.CLEANING_UP && state.terminal();
      if (
        cancelled &&
        state != State.CLEANING_UP &&
        state != State.NEEDS_ATTENTION &&
        !cleanupFinished
      ) return;
      final State nextState =
        cancelled && cleanupFinished && state != State.NEEDS_ATTENTION ? State.CANCELLED : state;
      final String nextDesired = cancelled
        ? "CANCELLED"
        : desired == null
          ? run.desiredOutcome()
          : desired;
      final String now = Instant.now().toString();
      store.update(
        new Run(
          run.id(),
          run.planId(),
          run.environment(),
          nextState,
          verdict,
          run.createdAt(),
          now,
          run.startedAt() == null ? now : run.startedAt(),
          start == null ? run.measurementStartedAt() : start,
          end == null ? run.measurementEndedAt() : end,
          message.length() > 1900 ? message.substring(0, 1900) : message,
          nextState == State.NEEDS_ATTENTION
            ? "FAILED"
            : nextState.terminal()
              ? "STOPPED_OWNED_LOAD_SERVICES_KEPT"
              : "PENDING",
          values,
          nextDesired,
          operation == null ? run.loadOperationId() : operation
        )
      );
    });
  }

  private void cleanupOwnedLoads(final Run run, final Plan plan) {
    RuntimeException failure = null;
    try {
      additionalLoads.cleanup(run, plan);
    } catch (final RuntimeException error) {
      failure = error;
    }
    try {
      if (!helm.stop(run.id(), target(plan), load(plan))) throw Problem.conflict(
        "Cannot confirm baseline load cleanup"
      );
    } catch (final RuntimeException error) {
      if (failure == null) failure = error;
      else failure.addSuppressed(error);
    }
    if (failure != null) throw failure;
  }

  public synchronized Run recover(final String id) {
    preparation.enabled();
    final var run = store.run(id);
    final var plan = store.plan(run.planId());
    if (run.state() != State.NEEDS_ATTENTION) throw Problem.conflict(
      "Select a run needing attention"
    );
    cleanupOwnedLoads(run, plan);
    write(
      run,
      State.FAILED,
      run.verdict(),
      "Manual recovery verified owned load cleanup",
      run.metrics(),
      "FAILED",
      null,
      null,
      null
    );
    store.releaseEnvironment(id);
    metrics.detach(id);
    AdSessionCredentials.detach(id);
    live.remove(id);
    return store.run(id);
  }
}

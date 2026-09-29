package com.example.perforchestrator.application;

import static com.example.perforchestrator.domain.Model.*;

import com.example.perforchestrator.domain.*;
import com.example.perforchestrator.infrastructure.config.Catalog;
import com.example.perforchestrator.infrastructure.persistence.Store;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class WorkflowWorker {
  private final Store store;
  private final Ports.DeploymentGateway deployments;
  private final Ports.LoadGeneratorGateway load;
  private final TransactionTemplate transactions;
  private final Catalog catalog;
  private final boolean enabled;

  public WorkflowWorker(
      Store store,
      Ports.DeploymentGateway deployments,
      Ports.LoadGeneratorGateway load,
      TransactionTemplate transactions,
      Catalog catalog,
      @Value("${orchestrator.worker-enabled:true}") boolean enabled) {
    this.store = store;
    this.deployments = deployments;
    this.load = load;
    this.transactions = transactions;
    this.catalog = catalog;
    this.enabled = enabled;
  }

  @Scheduled(fixedDelayString = "${orchestrator.tick-ms:1200}")
  public void scheduled() {
    if (enabled) tick();
  }

  public void tick() {
    if (catalog.mode().equals("real")) return;
    transactions.executeWithoutResult(
        status -> {
          store.lock();
          for (Run run : store.runs()) if (!run.state().terminal()) advance(run);
        });
  }

  private void advance(Run run) {
    Plan plan = store.plan(run.planId());
    Instant now = Instant.now();
    // Simulation operations and state commit in one DB transaction. Restart resumes the committed
    // stage.
    boolean owned = store.renewEnvironment(run.id(), "local-simulation", now, now.plusSeconds(30));
    if (!owned) {
      load.stop(run.id());
      write(
          run,
          State.NEEDS_ATTENTION,
          Verdict.INCONCLUSIVE,
          "Lease ownership missing; simulated load stopped",
          run.metrics(),
          "OWNERSHIP_LOST",
          "NEEDS_ATTENTION",
          run.loadOperationId(),
          run.measurementStartedAt(),
          run.measurementEndedAt());
      return;
    }
    try {
      if (!Set.of(State.CLEANING_UP, State.CANCEL_REQUESTED).contains(run.state())
          && now.isAfter(
              Instant.parse(run.createdAt()).plusSeconds(plan.profile().maxRunDurationSeconds())))
        throw Problem.invalid("deadline", "Maximum run duration exceeded");
      switch (run.state()) {
        case QUEUED ->
            step(
                run, State.PREFLIGHT, "Validating saved inputs and simulated environment baseline");
        case PREFLIGHT -> {
          // Accepted runs execute their persisted plan even after configuration edits.
          for (var service : plan.services())
            if (!deployments
                .baseline(plan.clusterIdentity(), service.namespace(), service.serviceId())
                .equals(service.baselineDigest()))
              throw Problem.conflict(
                  "Baseline drift detected for "
                      + service.serviceId()
                      + "; replan before deployment");
          step(
              run,
              plan.services().stream().anyMatch(s -> s.action() == Action.DEPLOY)
                  ? State.DEPLOYING
                  : State.WAITING_FOR_READY,
              "Preflight passed for simulated namespaces");
        }
        case DEPLOYING -> {
          for (var service : plan.services())
            if (service.action() == Action.DEPLOY) deployments.deploy(run.id(), plan, service);
          step(
              run,
              State.WAITING_FOR_READY,
              "Prepared images applied to simulated service namespaces");
        }
        case WAITING_FOR_READY -> {
          for (var service : plan.services())
            if (!deployments.ready(plan, service))
              throw Problem.invalid(
                  "readiness",
                  "Simulated rollout readiness timed out for "
                      + service.serviceId()
                      + "; load was not started");
          step(run, State.STARTING_LOAD, "All selected simulated image identities are ready");
        }
        case STARTING_LOAD -> {
          String op = load.start(run.id(), plan);
          Instant measurement = now.plusSeconds(plan.profile().loadGenerator().warmupSeconds());
          write(
              run,
              State.RUNNING_LOAD,
              run.verdict(),
              "Simulated load started; warmup is excluded from measurements",
              run.metrics(),
              run.cleanupOutcome(),
              run.desiredOutcome(),
              op,
              measurement.toString(),
              null);
        }
        case RUNNING_LOAD -> {
          if (now.isBefore(
              Instant.parse(run.measurementStartedAt())
                  .plusSeconds(plan.profile().loadGenerator().measurementSeconds()))) return;
          load.stop(run.id());
          write(
              run,
              State.COLLECTING,
              run.verdict(),
              "Measurement window ended; collecting synthetic results",
              run.metrics(),
              run.cleanupOutcome(),
              run.desiredOutcome(),
              run.loadOperationId(),
              run.measurementStartedAt(),
              now.toString());
        }
        case COLLECTING ->
            write(
                run,
                State.EVALUATING,
                run.verdict(),
                "Synthetic result collection complete",
                load.collect(run.id(), plan),
                run.cleanupOutcome(),
                run.desiredOutcome(),
                run.loadOperationId(),
                run.measurementStartedAt(),
                run.measurementEndedAt());
        case EVALUATING -> {
          Verdict verdict = evaluate(plan, run.metrics());
          write(
              run,
              State.CLEANING_UP,
              verdict,
              "Performance verdict: " + verdict + "; stopping owned resources",
              run.metrics(),
              "PENDING",
              run.desiredOutcome(),
              run.loadOperationId(),
              run.measurementStartedAt(),
              run.measurementEndedAt());
        }
        case CANCEL_REQUESTED -> {
          load.stop(run.id());
          write(
              run,
              State.CLEANING_UP,
              Verdict.NOT_EVALUATED,
              "Owned simulated load stopped; preserving available evidence",
              run.metrics(),
              "PENDING",
              "CANCELLED",
              run.loadOperationId(),
              run.measurementStartedAt(),
              now.toString());
        }
        case CLEANING_UP -> {
          load.stop(run.id());
          boolean failed = plan.profile().simulationCase() == SimulationCase.CLEANUP_FAILURE;
          if (!load.stopped(run.id()) || failed) {
            write(
                run,
                State.NEEDS_ATTENTION,
                run.verdict(),
                "Simulated cleanup failure. Load is stopped; environment remains reserved. Use"
                    + " recovery to verify cleanup.",
                run.metrics(),
                "FAILED",
                "NEEDS_ATTENTION",
                run.loadOperationId(),
                run.measurementStartedAt(),
                run.measurementEndedAt());
          } else {
            State outcome = State.valueOf(run.desiredOutcome());
            write(
                run,
                outcome,
                run.verdict(),
                "Simulation finished. " + run.message(),
                run.metrics(),
                "STOPPED_OWNED_LOAD_SERVICES_KEPT",
                run.desiredOutcome(),
                run.loadOperationId(),
                run.measurementStartedAt(),
                run.measurementEndedAt());
            store.releaseEnvironment(run.id());
          }
        }
        default -> {}
      }
    } catch (Problem e) {
      load.stop(run.id());
      write(
          run,
          State.CLEANING_UP,
          Verdict.NOT_EVALUATED,
          e.getMessage(),
          run.metrics(),
          "PENDING",
          "FAILED",
          run.loadOperationId(),
          run.measurementStartedAt(),
          now.toString());
    }
  }

  public static Verdict evaluate(Plan plan, Map<String, Double> metrics) {
    if (metrics.getOrDefault("request_count", 0.0) <= 0 || plan.profile().thresholds().isEmpty())
      return Verdict.INCONCLUSIVE;
    boolean failed = false;
    for (var threshold : plan.profile().thresholds()) {
      Double value = metrics.get(threshold.metric());
      if ((value == null || !Double.isFinite(value)) && threshold.required())
        return Verdict.INCONCLUSIVE;
      if (value != null && value > threshold.maximum()) failed = true;
    }
    return failed ? Verdict.FAIL : Verdict.PASS;
  }

  private void step(Run run, State next, String message) {
    write(
        run,
        next,
        run.verdict(),
        message,
        run.metrics(),
        run.cleanupOutcome(),
        run.desiredOutcome(),
        run.loadOperationId(),
        run.measurementStartedAt(),
        run.measurementEndedAt());
  }

  private void write(
      Run run,
      State state,
      Verdict verdict,
      String message,
      Map<String, Double> metrics,
      String cleanup,
      String desired,
      String op,
      String start,
      String end) {
    String now = Instant.now().toString();
    store.update(
        new Run(
            run.id(),
            run.planId(),
            run.environment(),
            state,
            verdict,
            run.createdAt(),
            now,
            run.startedAt() == null ? now : run.startedAt(),
            start,
            end,
            message,
            cleanup,
            metrics,
            desired,
            op));
  }

  public Run recover(String id) {
    catalog.requireExecution();
    return transactions.execute(
        status -> {
          store.lock();
          Run run = store.run(id);
          if (run.state() != State.NEEDS_ATTENTION)
            throw Problem.conflict("Only runs needing attention can be recovered");
          load.stop(id);
          if (!load.stopped(id)) throw Problem.conflict("Owned load is still active");
          write(
              run,
              State.FAILED,
              run.verdict(),
              "Manual simulation recovery verified stopped load and released environment",
              run.metrics(),
              "RECOVERED_SERVICES_KEPT",
              "FAILED",
              run.loadOperationId(),
              run.measurementStartedAt(),
              run.measurementEndedAt());
          store.releaseEnvironment(id);
          store.audit("SIMULATION_RECOVERED", id);
          return store.run(id);
        });
  }
}

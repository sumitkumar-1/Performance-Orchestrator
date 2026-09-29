package com.example.perforchestrator.application;

import static com.example.perforchestrator.domain.Model.*;

import com.example.perforchestrator.domain.*;
import com.example.perforchestrator.infrastructure.config.*;
import com.example.perforchestrator.infrastructure.persistence.Store;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RunService {
  public record Submission(String planId, String profileId, Integer revision) {}

  private final Store store;
  private final PlanningService planning;
  private final Catalog catalog;

  public RunService(Store store, PlanningService planning, Catalog catalog) {
    this.store = store;
    this.planning = planning;
    this.catalog = catalog;
  }

  @Transactional
  public Run enqueue(String key, Submission request) {
    catalog.requireExecution();
    if (key == null || !key.matches("[a-zA-Z0-9_-]{8,128}"))
      throw Problem.invalid("Idempotency-Key", "Supply a unique key of 8–128 safe characters");
    if ((request.planId() == null) == (request.profileId() == null))
      throw Problem.invalid("planId", "Supply exactly one planId or profileId");
    String requestHash = Json.hash(Json.write(request));
    store.lock();
    var previous = store.submission(key);
    if (previous.isPresent()) {
      if (!previous.get().requestHash().equals(requestHash))
        throw Problem.conflict("Idempotency key already belongs to a different request");
      return store.run(previous.get().runId());
    }
    Plan plan =
        request.planId() != null
            ? store.plan(request.planId())
            : planning.create(request.profileId(), request.revision(), null);
    if (!PlanningService.checksum(plan).equals(plan.checksum()))
      throw Problem.conflict("Plan checksum failed");
    if (Instant.now().isAfter(Instant.parse(plan.expiresAt()))
        || !plan.catalogHash().equals(catalog.hash()))
      throw Problem.conflict("Plan is stale; prepare a fresh plan");
    String scope = plan.clusterIdentity() + "/" + plan.profile().targetEnvironment();
    if (!store.environmentAvailable(scope))
      throw Problem.conflict("Environment is reserved by another run");
    Instant now = Instant.now();
    String id = UUID.randomUUID().toString();
    Run run =
        new Run(
            id,
            plan.id(),
            plan.profile().targetEnvironment(),
            State.QUEUED,
            Verdict.NOT_EVALUATED,
            now.toString(),
            now.toString(),
            null,
            null,
            null,
            "Queued for simulation",
            "PENDING",
            Map.of(),
            "SUCCEEDED",
            null);
    store.insert(run);
    store.reserveEnvironment(scope, id, "local-simulation", now, now.plusSeconds(30));
    store.recordSubmission(key, requestHash, id);
    store.audit("RUN_ENQUEUED", id);
    return run;
  }

  @Transactional
  public Run cancel(String id) {
    store.lock();
    Run run = store.run(id);
    if (run.state().terminal() || run.state() == State.CANCEL_REQUESTED) return run;
    Run next =
        new Run(
            run.id(),
            run.planId(),
            run.environment(),
            State.CANCEL_REQUESTED,
            run.verdict(),
            run.createdAt(),
            Instant.now().toString(),
            run.startedAt(),
            run.measurementStartedAt(),
            run.measurementEndedAt(),
            "Cancellation requested; owned load must stop before completion",
            run.cleanupOutcome(),
            run.metrics(),
            "CANCELLED",
            run.loadOperationId());
    store.update(next);
    store.audit("CANCEL_REQUESTED", id);
    return next;
  }
}

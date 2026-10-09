package com.example.perforchestrator.api;

import com.example.perforchestrator.application.*;
import com.example.perforchestrator.infrastructure.execution.ExecutionSettings;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/execution")
public class ExecutionController {

  private final com.example.perforchestrator.infrastructure.diagnostics.DiagnosticLog diagnostics;
  private final AdditionalLoads additionalLoads;
  private final RunPreparation preparation;
  private final RunExecution runs;
  private final ExecutionSettings settings;
  private final RunProfiles profiles;

  public ExecutionController(
    final RunPreparation preparation,
    final RunExecution runs,
    final ExecutionSettings settings,
    final RunProfiles profiles,
    final AdditionalLoads additionalLoads,
    final com.example.perforchestrator.infrastructure.diagnostics.DiagnosticLog diagnostics
  ) {
    this.additionalLoads = additionalLoads;
    this.diagnostics = diagnostics;
    this.profiles = profiles;
    this.preparation = preparation;
    this.runs = runs;
    this.settings = settings;
  }

  @GetMapping("/settings")
  public Object settings() {
    return Map.of(
      "enabled",
      settings.enabled,
      "kubeContext",
      settings.context,
      "expectedApiServer",
      settings.expectedServer,
      "commandTimeoutSeconds",
      settings.timeoutSeconds,
      "defaultRunDurationSeconds",
      settings.defaultRunDurationSeconds,
      "maxRunDurationSeconds",
      settings.maxRunDurationSeconds,
      "targets",
      settings.targets,
      "defaultEnvironment",
      settings.defaultEnvironment
    );
  }

  @GetMapping("/profiles")
  public Object profiles(final @RequestParam(required = false) String environment) {
    return profiles.list(environment);
  }

  public record ProfileInput(String id, Integer revision, RunPreparation.Request profile) {}

  @PostMapping("/profiles")
  public Object save(final @RequestBody ProfileInput input) {
    return profiles.save(input.id(), input.revision(), input.profile());
  }

  public record SourceRequest(String revision) {}

  @PostMapping("/services/{service}/values")
  public Object values(
    final @PathVariable String service,
    final @RequestBody SourceRequest request
  ) {
    return preparation.profiles(service, request.revision());
  }

  @GetMapping("/preparations/{id}")
  public Object progress(
    final @PathVariable String id,
    final jakarta.servlet.http.HttpServletRequest request
  ) {
    final var session = request.getSession(false);
    final var value = session == null ? null : session.getAttribute("reviewProgress");
    if (
      !(value instanceof final ReviewProgress review) || !review.id().equals(id)
    ) throw com.example.perforchestrator.domain.Problem.missing("Review progress");
    return review.snapshot();
  }

  @PostMapping("/plans")
  public Object prepare(
    final @RequestBody RunPreparation.Request request,
    @RequestHeader(value = "X-Diagnostic-ID", required = false) String trace,
    final @RequestHeader(value = "X-Review-ID", required = false) String reviewId,
    final jakarta.servlet.http.HttpServletRequest servletRequest
  ) {
    if (
      reviewId != null && !reviewId.matches("[a-zA-Z0-9-]{1,80}")
    ) throw com.example.perforchestrator.domain.Problem.invalid(
      "reviewId",
      "Invalid review identifier"
    );
    final var progress = new ReviewProgress(
      reviewId == null ? java.util.UUID.randomUUID().toString() : reviewId,
      request
    );
    servletRequest.getSession().setAttribute("reviewProgress", progress);
    if (trace == null) trace = diagnostics.create(null);
    try (var scope = diagnostics.scope(trace)) {
      final var plan = preparation.prepare(request, progress::step);
      diagnostics.plan(trace, plan.id());
      progress.finish(true);
      return plan;
    } catch (final RuntimeException error) {
      progress.finish(false);
      throw error;
    }
  }

  @GetMapping("/runs/{id}/loads")
  public Object loads(final @PathVariable String id) {
    return additionalLoads
      .list(id)
      .stream()
      .map((final var load) -> loadView(load, false))
      .toList();
  }

  @PostMapping("/runs/{id}/loads/review")
  public Object reviewLoad(
    final @PathVariable String id,
    final @RequestBody RunPreparation.Deployment request
  ) {
    try (var scope = diagnostics.scope(diagnostics.forRun(id))) {
      return loadView(additionalLoads.prepare(id, request), true);
    }
  }

  @PostMapping("/runs/{id}/loads/{loadId}/start")
  public Object startLoad(final @PathVariable String id, final @PathVariable String loadId) {
    try (var scope = diagnostics.scope(diagnostics.forRun(id))) {
      return loadView(additionalLoads.enqueue(id, loadId), false);
    }
  }

  @GetMapping("/runs/{id}/loads/baseline")
  public Object baselineLoad(final @PathVariable String id) {
    return java.util.Map.of("stopped", runs.baselineStopped(id));
  }

  @PostMapping("/runs/{id}/loads/{loadId}/stop")
  public Object stopLoad(final @PathVariable String id, final @PathVariable String loadId) {
    try (var scope = diagnostics.scope(diagnostics.forRun(id))) {
      return runs.stopLoad(id, loadId);
    }
  }

  @PostMapping("/runs/{id}/cleanup-services")
  public Object cleanupServices(final @PathVariable String id) {
    try (var scope = diagnostics.scope(diagnostics.forRun(id))) {
      return java.util.Map.of("removed", runs.cleanupServices(id));
    }
  }

  private Object loadView(
    final com.example.perforchestrator.domain.AdditionalLoad load,
    final boolean includeValues
  ) {
    final var service = load.service();
    final var details = new java.util.LinkedHashMap<String, Object>();
    details.put("serviceId", service.serviceId());
    details.put("namespace", service.namespace());
    details.put("releaseName", service.releaseName());
    details.put("image", service.image());
    details.put("sourceRevision", service.sourceRevision());
    if (includeValues) details.put("effectiveValues", service.effectiveValues());
    return Map.of(
      "id",
      load.id(),
      "actor",
      load.actor(),
      "createdAt",
      load.createdAt(),
      "updatedAt",
      load.updatedAt(),
      "state",
      load.state(),
      "message",
      load.message(),
      "service",
      details
    );
  }

  public record Submit(String planId) {}

  @PostMapping("/runs")
  public Object run(
    final @RequestHeader("Idempotency-Key") String key,
    final @RequestBody Submit request
  ) {
    final String trace = diagnostics.create(diagnostics.forPlan(request.planId()));
    try (var scope = diagnostics.scope(trace)) {
      final var run = runs.enqueue(key, request.planId());
      if (diagnostics.forRun(run.id()) == null) diagnostics.run(trace, run.id());
      return run;
    }
  }

  @PostMapping("/runs/{id}/recover")
  public Object recover(final @PathVariable String id) {
    try (var scope = diagnostics.scope(diagnostics.forRun(id))) {
      return runs.recover(id);
    }
  }
}

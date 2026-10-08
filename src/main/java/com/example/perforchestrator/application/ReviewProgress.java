package com.example.perforchestrator.application;

import java.time.Instant;
import java.util.*;

/** One browser-session review, containing only service IDs and fixed stage labels. */
public final class ReviewProgress {

  public record Step(String serviceId, String stage, String startedAt, String updatedAt) {}

  private final String id,
    startedAt = Instant.now().toString();
  private String state = "RUNNING",
    stage = "Checking cluster connection";
  private final Map<String, Step> steps = new LinkedHashMap<>();

  public ReviewProgress(final String id, final RealPreparation.Request request) {
    this.id = id;
    if (request.services() != null) request
      .services()
      .stream()
      .filter(Objects::nonNull)
      .forEach((final var s) -> step(s.serviceId(), "Queued"));
    if (request.loadGenerator() != null) step(request.loadGenerator().serviceId(), "Queued");
  }

  public String id() {
    return id;
  }

  public synchronized void step(final String service, final String label) {
    if (service == null) {
      stage = label;
      return;
    }
    final var old = steps.get(service);
    final String now = Instant.now().toString();
    final String began =
      old == null || old.startedAt() == null
        ? label.equals("Queued")
          ? null
          : now
        : old.startedAt();
    steps.put(service, new Step(service, label, began, now));
  }

  public synchronized void finish(final boolean success) {
    state = success ? "READY" : "FAILED";
    stage = success ? "Ready for review" : "Review failed";
  }

  public synchronized Map<String, Object> snapshot() {
    return Map.of(
      "id",
      id,
      "state",
      state,
      "stage",
      stage,
      "startedAt",
      startedAt,
      "services",
      List.copyOf(steps.values())
    );
  }
}

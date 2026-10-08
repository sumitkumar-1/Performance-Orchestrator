package com.example.perforchestrator.domain;

import com.example.perforchestrator.domain.Model.PreparedService;

/** A frozen, independently owned Helm installation attached to a parent run. */
public record AdditionalLoad(
  String id,
  String runId,
  String actor,
  String createdAt,
  String updatedAt,
  String expiresAt,
  String catalogHash,
  String connectionHash,
  String state,
  String message,
  String chart,
  PreparedService service
) {
  public AdditionalLoad transition(final String next, final String detail) {
    return new AdditionalLoad(
      id,
      runId,
      actor,
      createdAt,
      java.time.Instant.now().toString(),
      expiresAt,
      catalogHash,
      connectionHash,
      next,
      detail,
      chart,
      service
    );
  }
}

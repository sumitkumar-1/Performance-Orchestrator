package com.example.perforchestrator.application;

import com.example.perforchestrator.domain.Model.Plan;
import com.example.perforchestrator.infrastructure.config.Json;

public final class PlanChecksums {

  private PlanChecksums() {}

  public static String checksum(final Plan p) {
    return Json.hash(
      Json.write(
        new Plan(
          p.id(),
          "",
          p.createdAt(),
          p.expiresAt(),
          p.actor(),
          p.profileId(),
          p.profileRevision(),
          p.profile(),
          p.catalogHash(),
          p.clusterIdentity(),
          p.loadNamespace(),
          p.scenarioRevision(),
          p.effectiveLoadConfiguration(),
          p.services(),
          p.warnings()
        )
      )
    );
  }
}

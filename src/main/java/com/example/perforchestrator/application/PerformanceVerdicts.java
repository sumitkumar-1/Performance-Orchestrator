package com.example.perforchestrator.application;

import static com.example.perforchestrator.domain.Model.*;

import java.util.Map;

public final class PerformanceVerdicts {

  private PerformanceVerdicts() {}

  public static Verdict evaluate(final Plan plan, final Map<String, Double> metrics) {
    if (
      metrics.getOrDefault("request_count", 0.0) <= 0 || plan.profile().thresholds().isEmpty()
    ) return Verdict.INCONCLUSIVE;
    boolean failed = false;
    for (final var threshold : plan.profile().thresholds()) {
      final Double value = metrics.get(threshold.metric());
      if (
        (value == null || !Double.isFinite(value)) && threshold.required()
      ) return Verdict.INCONCLUSIVE;
      if (value != null && value > threshold.maximum()) failed = true;
    }
    return failed ? Verdict.FAIL : Verdict.PASS;
  }
}

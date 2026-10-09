package com.example.perforchestrator.domain;

import java.util.List;
import java.util.Map;

/** Transport-independent, immutable workflow values. Nested maps are snapshotted by planning. */
public final class Model {

  private Model() {}

  public enum Action {
    DEPLOY,
    VERIFY_EXISTING,
  }

  public enum State {
    QUEUED,
    PREFLIGHT,
    DEPLOYING,
    WAITING_FOR_READY,
    STARTING_LOAD,
    RUNNING_LOAD,
    COLLECTING,
    AWAITING_LOAD_STOP,
    EVALUATING,
    CANCEL_REQUESTED,
    CLEANING_UP,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    NEEDS_ATTENTION;

    public boolean terminal() {
      return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == NEEDS_ATTENTION;
    }
  }

  public enum Verdict {
    PASS,
    FAIL,
    INCONCLUSIVE,
    NOT_EVALUATED,
  }

  public record Build(String sourceRef, String username, String version) {}

  public record Selection(String serviceId, Action action, Build build, String valuesOverlay) {}

  public record Load(
    String templateRef,
    int virtualUsers,
    int requestsPerSecond,
    int warmupSeconds,
    int measurementSeconds,
    String configurationOverlay
  ) {}

  public record Threshold(String metric, double maximum, boolean required) {}

  @com.fasterxml.jackson.annotation.JsonIgnoreProperties("simulationCase")
  public record Profile(
    String name,
    String targetEnvironment,
    List<Selection> services,
    Load loadGenerator,
    int maxRunDurationSeconds,
    List<Threshold> thresholds
  ) {}

  public record Image(
    String sourceRef,
    String username,
    String repository,
    String version,
    String digest
  ) {}

  public record PreparedService(
    String serviceId,
    Action action,
    String namespace,
    String releaseName,
    Image image,
    String baselineDigest,
    String sourceRevision,
    Map<String, String> originalHashes,
    Map<String, String> preparedFiles,
    Map<String, String> preparedHashes,
    Map<String, Object> effectiveValues,
    Map<String, Object> changes,
    List<String> versionArguments
  ) {}

  @com.fasterxml.jackson.annotation.JsonIgnoreProperties("simulated")
  public record Plan(
    String id,
    String checksum,
    String createdAt,
    String expiresAt,
    String actor,
    String profileId,
    int profileRevision,
    Profile profile,
    String catalogHash,
    String clusterIdentity,
    String loadNamespace,
    String scenarioRevision,
    Map<String, Object> effectiveLoadConfiguration,
    List<PreparedService> services,
    List<String> warnings
  ) {}

  public record Run(
    String id,
    String planId,
    String environment,
    State state,
    Verdict verdict,
    String createdAt,
    String updatedAt,
    String startedAt,
    String measurementStartedAt,
    String measurementEndedAt,
    String message,
    String cleanupOutcome,
    Map<String, Double> metrics,
    String desiredOutcome,
    String loadOperationId
  ) {}

  public record Event(long id, String runId, String time, String state, String message) {}
}

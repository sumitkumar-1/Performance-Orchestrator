package com.example.perforchestrator.application;

import com.example.perforchestrator.domain.Problem;
import com.example.perforchestrator.infrastructure.config.Catalog;
import com.example.perforchestrator.infrastructure.execution.*;
import com.example.perforchestrator.infrastructure.persistence.Store;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/** Explicit, previewed cleanup of catalog-selected releases, including pre-existing installations. */
@Service
public class EnvironmentCleanup {

  public record Selection(String environment, List<String> services) {}

  public record Release(String service, String namespace, String release, String baseline) {}

  public record Preview(
    String id,
    String environment,
    String scope,
    String catalogHash,
    Map<String, Object> target,
    Instant expiresAt,
    List<Release> releases
  ) {}

  private final Catalog catalog;
  private final HelmExecution helm;
  private final ExecutionSettings settings;
  private final Store store;

  public EnvironmentCleanup(
    final Catalog catalog,
    final HelmExecution helm,
    final ExecutionSettings settings,
    final Store store
  ) {
    this.catalog = catalog;
    this.helm = helm;
    this.settings = settings;
    this.store = store;
  }

  private void enabled() {
    if (!settings.enabled) throw Problem.conflict("Enable cluster execution before cleanup");
  }

  private void available(final String scope) {
    if (!store.environmentAvailable(scope)) throw Problem.conflict(
      "Environment is reserved by a run. Stop its loads and confirm run cleanup before cleaning selected releases."
    );
  }

  public Preview preview(final Selection selection) {
    enabled();
    if (
      selection == null ||
      selection.services() == null ||
      selection.services().isEmpty() ||
      selection.services().size() > 31
    ) throw Problem.invalid("services", "Select 1–31 services for cleanup");
    final String environment = catalog.selectedEnvironment(selection.environment());
    final String scope = catalog.environment(environment).clusterIdentity() + "/" + environment;
    synchronized (store) {
      available(scope);
      final var target = helm.target(environment);
      final var releases = new ArrayList<Release>();
      final Set<String> destinations = new HashSet<>();
      for (final String id : selection.services()) {
        final var service = catalog.service(id);
        final var destination = service
          .deploymentByEnvironment()
          .getOrDefault(environment, service.deploymentDefaults());
        if (
          destination == null ||
          destination.namespace() == null ||
          destination.releaseName() == null ||
          !destination.namespace().matches("[a-z0-9][a-z0-9-]{0,62}") ||
          !destination.releaseName().matches("[a-z0-9][a-z0-9-]{0,52}")
        ) throw Problem.invalid("services", "Configure a valid namespace and release for " + id);
        if (
          !destinations.add(destination.namespace() + "/" + destination.releaseName())
        ) throw Problem.invalid("services", "Selected services share a release");
        releases.add(
          new Release(
            id,
            destination.namespace(),
            destination.releaseName(),
            helm.baseline(target, destination.namespace(), destination.releaseName())
          )
        );
      }
      return new Preview(
        UUID.randomUUID().toString(),
        environment,
        scope,
        catalog.hash(),
        Map.copyOf(target),
        Instant.now().plusSeconds(300),
        List.copyOf(releases)
      );
    }
  }

  public List<String> execute(final Preview preview) {
    enabled();
    // Queue admission uses this same monitor. No local run can reserve the environment mid-cleanup.
    synchronized (store) {
      if (preview == null || Instant.now().isAfter(preview.expiresAt())) throw Problem.conflict(
        "Cleanup preview expired; preview again"
      );
      available(preview.scope());
      if (
        !preview.catalogHash().equals(catalog.hash()) ||
        !preview.target().equals(helm.target(preview.environment()))
      ) throw Problem.conflict("Configuration changed; preview cleanup again");
      // Check the entire approved set before any destructive command.
      for (final var release : preview.releases()) {
        if (
          !release
            .baseline()
            .equals(helm.baseline(preview.target(), release.namespace(), release.release()))
        ) throw Problem.conflict(
          "Release changed since cleanup preview: " +
            release.namespace() +
            "/" +
            release.release() +
            ". Preview again."
        );
      }
      final var removed = new ArrayList<String>();
      for (final var release : preview.releases().reversed()) {
        if (release.baseline().equals("ABSENT")) continue;
        final String identity = release.namespace() + "/" + release.release();
        try {
          helm.cleanupApprovedRelease(
            preview.target(),
            release.namespace(),
            release.release(),
            release.baseline()
          );
          removed.add(identity);
          store.audit("ENVIRONMENT_RELEASE_CLEANED", preview.environment() + "/" + identity);
        } catch (final RuntimeException error) {
          throw Problem.conflict(
            "Cleanup stopped at " +
              identity +
              ". " +
              removed.size() +
              " earlier release(s) were removed; preparation has not started. " +
              (error instanceof Problem
                ? error.getMessage()
                : "Inspect diagnostics and preview cleanup again.")
          );
        }
      }
      return List.copyOf(removed);
    }
  }
}

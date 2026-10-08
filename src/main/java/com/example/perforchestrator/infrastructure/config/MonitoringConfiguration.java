package com.example.perforchestrator.infrastructure.config;

import com.example.perforchestrator.infrastructure.registry.ConnectionConfig;
import java.util.*;

/** Upgrade legacy URL/namespace placement without discarding unmatched or distinct metric settings. */
public final class MonitoringConfiguration {

  private MonitoringConfiguration() {}

  public record Result(Catalog.Data catalog, ConnectionConfig.Data connections) {}

  public static Result migrate(
    final Catalog.Data catalog,
    final ConnectionConfig.Data connections
  ) {
    if (catalog.environments() == null || catalog.services() == null) return new Result(
      catalog,
      connections
    );
    final var environments = new LinkedHashMap<>(catalog.environments());
    final var services = new LinkedHashMap<>(catalog.services());
    final var loki = new LinkedHashMap<>(connections.loki());
    environments.replaceAll((final var environment, final var env) -> {
      if (env == null || env.monitoring() == null) return env;
      final var old = env.monitoring();
      String ref = old.connectionRef();
      if (old.logsApiBaseUrl() != null && !old.logsApiBaseUrl().isBlank()) {
        final String url = old.logsApiBaseUrl();
        ref = loki
          .entrySet()
          .stream()
          .filter((final var entry) -> entry.getValue().apiBaseUrl().equals(url))
          .map(Map.Entry::getKey)
          .findFirst()
          .orElse(null);
        if (ref == null) {
          ref = "migrated-" + Json.hash(url).substring(0, 16);
          final var existing = loki.get(ref);
          if (
            existing != null && !existing.apiBaseUrl().equals(url)
          ) throw new IllegalArgumentException("Conflicting migrated Loki connection");
          loki.put(ref, new ConnectionConfig.Loki(url));
        }
      }
      final var remaining = new LinkedHashMap<>(
        old.namespaceCredentials() == null
          ? Map.<String, Catalog.NamespaceCredentials>of()
          : old.namespaceCredentials()
      );
      for (final var entry : new ArrayList<>(remaining.entrySet())) {
        final String namespace = entry.getKey();
        final var credentials = entry.getValue();
        boolean matched = false;
        if (credentials == null) continue;
        for (final var serviceEntry : new ArrayList<>(services.entrySet())) {
          final var service = serviceEntry.getValue();
          if (service == null) continue;
          final var destination = service
            .deploymentByEnvironment()
            .getOrDefault(environment, service.deploymentDefaults());
          if (destination == null || !namespace.equals(destination.namespace())) continue;
          final var references = new LinkedHashMap<>(service.monitoringCredentials());
          if (
            credentials.logsCredentialRef() != null && !credentials.logsCredentialRef().isBlank()
          ) references.put(environment, credentials.logsCredentialRef());
          services.put(
            serviceEntry.getKey(),
            new Catalog.Service(
              service.projectPath(),
              service.dependencies(),
              service.deploymentByEnvironment(),
              service.installationBindings(),
              service.allowedOverridePaths(),
              service.deploymentDefaults(),
              service.containerImage(),
              service.sourceProject(),
              references
            )
          );
          matched = true;
        }
        // Different legacy Prometheus credentials cannot safely be relabelled as Loki credentials.
        if (
          matched &&
          (credentials.metricsCredentialRef() == null ||
            Objects.equals(credentials.metricsCredentialRef(), credentials.logsCredentialRef()))
        ) remaining.remove(namespace);
      }
      String metrics = old.metricsApiBaseUrl();
      if (Objects.equals(metrics, old.logsApiBaseUrl())) metrics = null;
      final var monitoring = new Catalog.Monitoring(
        null,
        metrics,
        remaining.isEmpty() ? null : remaining,
        ref
      );
      return new Catalog.Environment(
        env.displayName(),
        env.clusterIdentity(),
        env.serviceNamespaces(),
        env.loadGeneratorNamespace(),
        env.allowedActions(),
        env.limits(),
        env.dashboardUrl(),
        monitoring
      );
    });
    return new Result(
      new Catalog.Data(
        catalog.mode(),
        environments,
        services,
        catalog.imageSources(),
        catalog.scenarios()
      ),
      new ConnectionConfig.Data(
        connections.artifactory(),
        connections.secretServers(),
        connections.credentials(),
        connections.imageSources(),
        connections.bitbucket(),
        loki
      )
    );
  }
}

package com.example.perforchestrator.infrastructure.execution;

import com.example.perforchestrator.application.RunPreparation;
import com.example.perforchestrator.domain.*;
import com.example.perforchestrator.domain.Model.*;
import com.example.perforchestrator.infrastructure.config.*;
import com.example.perforchestrator.infrastructure.registry.*;
import com.example.perforchestrator.infrastructure.secrets.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class LokiMeasurements {

  private record Query(String name, String url, String authorization, String query) {
    @Override
    public String toString() {
      return "[REDACTED monitoring query]";
    }
  }

  private final Map<String, List<Query>> runs = new ConcurrentHashMap<>();
  private final Catalog catalog;
  private final ConnectionConfig connections;
  private final CredentialResolver credentials;
  private final ReadOnlyHttp http;

  public LokiMeasurements(
    final Catalog catalog,
    final ConnectionConfig connections,
    final CredentialResolver credentials,
    final ReadOnlyHttp http
  ) {
    this.catalog = catalog;
    this.connections = connections;
    this.credentials = credentials;
    this.http = http;
  }

  public void attach(final String runId, final Plan plan) {
    final List<Query> queries = new ArrayList<>();
    final var array = Json.MAPPER.valueToTree(plan.effectiveLoadConfiguration().get("metrics"));
    for (final var item : array) {
      final var metric = Json.read(item.toString(), RunPreparation.Metric.class);
      if (metric.logs()) continue;
      final var service = catalog.service(metric.serviceId());
      final var env = catalog.environment(plan.profile().targetEnvironment());
      final String reference =
        metric.credentialRef() == null || metric.credentialRef().isBlank()
          ? service.monitoringCredentials().get(plan.profile().targetEnvironment())
          : metric.credentialRef();
      if (reference == null || env.monitoring() == null) throw Problem.invalid(
        "metrics",
        "Configure monitoring credentials and the environment Loki connection"
      );
      final var loki = connections.data().loki().get(env.monitoring().connectionRef());
      if (loki == null) throw Problem.invalid("metrics", "Unknown Loki connection");
      final var secret = credentials.resolve(reference);
      final String authorization = secret.token()
        ? RequestAuthentication.bearer(secret.password())
        : RequestAuthentication.basic(secret.username(), secret.password());
      final String ns = com.example.perforchestrator.application.RunMonitoring.namespace(
        metric,
        service,
        plan.profile().targetEnvironment()
      );
      final String query = com.example.perforchestrator.application.RunMonitoring.query(
        metric,
        ns,
        plan.profile().targetEnvironment(),
        plan.profile().loadGenerator().measurementSeconds()
      );
      queries.add(new Query(metric.name(), loki.apiBaseUrl(), authorization, query));
    }
    runs.put(runId, List.copyOf(queries));
  }

  public Map<String, Double> collect(final String runId, final Instant time) {
    final var queries = runs.get(runId);
    if (queries == null) return Map.of();
    final Map<String, Double> values = new TreeMap<>();
    for (final var query : queries) {
      final var uri = URI.create(
        query.url().replaceAll("/$", "") +
          "/query?query=" +
          URLEncoder.encode(query.query(), StandardCharsets.UTF_8) +
          "&time=" +
          time.getEpochSecond()
      );
      try {
        final var response = http.get(uri, query.authorization(), "application/json");
        ReadOnlyHttp.requireSuccess(response, "loki");
        final var body = Json.MAPPER.readTree(response.body());
        if (
          !body.path("status").asText().equals("success") ||
          !body.path("data").path("resultType").asText().equals("vector")
        ) continue;
        final var result = body.path("data").path("result");
        // Require one explicitly aggregated series, rather than guessing how unrelated series combine.
        if (!result.isArray() || result.size() != 1) continue;
        final double value = Double.parseDouble(result.get(0).path("value").get(1).asText());
        if (Double.isFinite(value)) values.put(query.name(), value);
      } catch (final Exception ignored) {
        /* Missing/rejected/malformed evidence remains unavailable, never zero or PASS. */
      }
    }
    return Map.copyOf(values);
  }

  public void detach(final String runId) {
    runs.remove(runId);
  }
}

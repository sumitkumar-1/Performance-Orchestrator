package com.example.perforchestrator.application;

import com.example.perforchestrator.domain.*;
import com.example.perforchestrator.domain.Model.*;
import com.example.perforchestrator.infrastructure.config.*;
import com.example.perforchestrator.infrastructure.persistence.Store;
import com.example.perforchestrator.infrastructure.registry.*;
import com.example.perforchestrator.infrastructure.secrets.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Live exploration is separate from frozen end-of-run verdict queries. No credentials are persisted. */
@Service
public class RunMonitoring {

  private final Store store;
  private final JdbcTemplate db;
  private final Catalog catalog;
  private final ConnectionConfig connections;
  private final CredentialResolver credentials;
  private final ReadOnlyHttp http;

  public RunMonitoring(
    final Store store,
    final JdbcTemplate db,
    final Catalog catalog,
    final ConnectionConfig connections,
    final CredentialResolver credentials,
    final ReadOnlyHttp http
  ) {
    this.store = store;
    this.db = db;
    this.catalog = catalog;
    this.connections = connections;
    this.credentials = credentials;
    this.http = http;
  }

  private Plan plan(final String id) {
    final var plan = store.plan(store.run(id).planId());
    if (plan.simulated()) throw Problem.invalid("run", "Select a real run");
    return plan;
  }

  public List<RealPreparation.Metric> panels(final String id) {
    final var plan = plan(id);
    final var stored = db.query(
      "SELECT body FROM run_monitoring WHERE run_id=?",
      (final var r, final var n) -> r.getString(1),
      id
    );
    final var array = stored.isEmpty()
      ? Json.MAPPER.valueToTree(plan.effectiveLoadConfiguration().get("metrics"))
      : Json.MAPPER.valueToTree(Json.read(stored.getFirst(), List.class));
    final var result = new ArrayList<RealPreparation.Metric>();
    for (final var item : array)
      result.add(Json.read(item.toString(), RealPreparation.Metric.class));
    return result;
  }

  public Object view(final String id) {
    final var plan = plan(id);
    final var run = store.run(id);
    long window = 900;
    for (final var service : plan.services())
      if (service.serviceId().equals(plan.effectiveLoadConfiguration().get("loadService"))) {
        final var generator = Json.MAPPER.valueToTree(service.effectiveValues()).path("generator");
        final String duration = generator
          .path("runTime")
          .asText(generator.path("runTIme").asText(""));
        try {
          final long seconds = Duration.parse(duration).getSeconds();
          if (seconds > 0) window = Math.min(seconds, 604800);
        } catch (final Exception ignored) {}
      }
    return Map.of(
      "panels",
      panels(id),
      "start",
      run.startedAt() == null ? run.createdAt() : run.startedAt(),
      "windowSeconds",
      window,
      "actor",
      plan.actor(),
      "environment",
      run.environment()
    );
  }

  public Object save(final String id, final List<RealPreparation.Metric> panels) {
    plan(id);
    if (panels == null || panels.size() > 20) throw Problem.invalid(
      "monitoring",
      "Use at most 20 panels"
    );
    final Set<String> names = new HashSet<>();
    for (final var panel : panels) {
      validate(panel, catalog, connections);
      if (!names.add(panel.name())) throw Problem.invalid("monitoring", "Panel IDs must be unique");
    }
    db.update(
      "MERGE INTO run_monitoring (run_id,body) KEY(run_id) VALUES (?,?)",
      id,
      Json.write(panels)
    );
    store.audit("MONITORING_UPDATED", id);
    return view(id);
  }

  public static void validate(
    final RealPreparation.Metric panel,
    final Catalog catalog,
    final ConnectionConfig connections
  ) {
    if (
      panel == null ||
      panel.name() == null ||
      !panel.name().matches("[a-z][a-z0-9_]{0,63}") ||
      panel.query() == null ||
      panel.query().isBlank() ||
      panel.query().length() > 8192 ||
      (panel.title() != null && panel.title().length() > 120) ||
      (panel.kind() != null && !Set.of("logs", "metric").contains(panel.kind()))
    ) throw Problem.invalid("monitoring", "Supply a panel ID, LogQL query and logs/metric type");
    catalog.service(panel.serviceId());
    if (
      panel.namespace() != null &&
      !panel.namespace().isBlank() &&
      !panel.namespace().matches("[A-Za-z0-9_.-]{1,120}")
    ) throw Problem.invalid("namespace", "Use a valid Loki namespace label");
    if (
      panel.credentialRef() != null &&
      !panel.credentialRef().isBlank() &&
      !connections.data().credentials().containsKey(panel.credentialRef())
    ) throw Problem.invalid("credentialRef", "Choose a configured credential reference");
  }

  public static String namespace(
    final RealPreparation.Metric panel,
    final Catalog.Service service,
    final String environment
  ) {
    if (panel.namespace() != null && !panel.namespace().isBlank()) return panel.namespace();
    final var dest = service
      .deploymentByEnvironment()
      .getOrDefault(environment, service.deploymentDefaults());
    if (dest == null) throw Problem.invalid(
      "namespace",
      "Supply the Loki namespace for this panel"
    );
    return dest.namespace();
  }

  public static String query(
    final RealPreparation.Metric panel,
    final String namespace,
    final String environment,
    final long seconds
  ) {
    final String result = panel
      .query()
      .replace("{{namespace}}", namespace)
      .replace("{{clusterEnv}}", environment)
      .replace("{{clustEnv}}", environment)
      .replace("{{durationSeconds}}", String.valueOf(seconds));
    if (result.contains("{{")) throw Problem.invalid("query", "Unresolved LogQL placeholder");
    return result;
  }

  public Object query(final String id, final int index, final String start, final String end) {
    final var plan = plan(id);
    final var panels = panels(id);
    if (index < 0 || index >= panels.size()) throw Problem.missing("Monitoring panel");
    final Instant from, to;
    try {
      from = Instant.parse(start);
      to = Instant.parse(end);
    } catch (final Exception error) {
      throw Problem.invalid("time", "Use ISO timestamps for start/end");
    }
    final long seconds = Duration.between(from, to).getSeconds();
    if (seconds < 1 || seconds > 604800) throw Problem.invalid(
      "time",
      "Choose a range between 1 second and 7 days"
    );
    final var panel = panels.get(index);
    validate(panel, catalog, connections);
    final var service = catalog.service(panel.serviceId());
    final var env = catalog.environment(plan.profile().targetEnvironment());
    if (env.monitoring() == null) throw Problem.invalid(
      "monitoring",
      "Configure this environment's Loki connection"
    );
    final var loki = connections.data().loki().get(env.monitoring().connectionRef());
    if (loki == null) throw Problem.invalid("monitoring", "Loki connection is missing");
    final String ref =
      panel.credentialRef() == null || panel.credentialRef().isBlank()
        ? service.monitoringCredentials().get(plan.profile().targetEnvironment())
        : panel.credentialRef();
    if (ref == null) throw Problem.invalid(
      "credentialRef",
      "Map a monitoring credential for this service/environment"
    );
    final var secret = credentials.resolve(ref);
    final String auth = secret.token()
      ? RequestAuthentication.bearer(secret.password())
      : RequestAuthentication.basic(secret.username(), secret.password());
    final String ns = namespace(panel, service, plan.profile().targetEnvironment());
    final String logql = query(panel, ns, plan.profile().targetEnvironment(), seconds);
    final var uri = URI.create(
      loki.apiBaseUrl().replaceAll("/$", "") +
        "/query_range?query=" +
        URLEncoder.encode(logql, StandardCharsets.UTF_8) +
        "&start=" +
        from.getEpochSecond() +
        "&end=" +
        to.getEpochSecond() +
        "&step=" +
        Math.max(1, (seconds + 999) / 1000) +
        "&limit=500&direction=backward"
    );
    final var response = http.get(uri, auth, "application/json");
    ReadOnlyHttp.requireSuccess(response, "loki");
    try {
      final var body = Json.MAPPER.readTree(response.body());
      final String type = body.path("data").path("resultType").asText();
      if (
        !body.path("status").asText().equals("success") ||
        !Set.of("matrix", "streams").contains(type) ||
        !body.path("data").path("result").isArray()
      ) throw new IllegalArgumentException();
      return Map.of(
        "resultType",
        type,
        "result",
        body.path("data").path("result"),
        "namespace",
        ns,
        "credentialRef",
        ref,
        "logLimit",
        500,
        "stepSeconds",
        Math.max(1, (seconds + 999) / 1000)
      );
    } catch (final Exception error) {
      throw new Problem(
        502,
        "LOKI_SCHEMA",
        "monitoring",
        "Loki did not return log streams or metric series"
      );
    }
  }
}

package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.perforchestrator.application.*;
import com.example.perforchestrator.domain.Model.*;
import com.example.perforchestrator.infrastructure.config.*;
import com.example.perforchestrator.infrastructure.persistence.Store;
import com.example.perforchestrator.infrastructure.registry.*;
import com.example.perforchestrator.infrastructure.secrets.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.DisplayName;
import org.springframework.jdbc.core.JdbcTemplate;

class RunMonitoringTest {

  final Store store = mock(Store.class);
  final JdbcTemplate db = mock(JdbcTemplate.class);
  final Catalog catalog = mock(Catalog.class);
  final ConnectionConfig connections = mock(ConnectionConfig.class);
  final CredentialResolver credentials = mock(CredentialResolver.class);
  final ReadOnlyHttp http = mock(ReadOnlyHttp.class);
  Plan plan;
  Run run;
  RunMonitoring monitoring;

  @BeforeEach
  void setup() {
    run = Json.read(
      "{\"planId\":\"plan\",\"environment\":\"sandbox\",\"startedAt\":\"2026-09-30T10:00:00Z\"}",
      Run.class
    );
    when(store.run("run")).thenReturn(run);
    final var service = new Catalog.Service(
      "project",
      List.of(),
      Map.of(),
      null,
      List.of(),
      new Catalog.Destination("deployment-ns", "release", List.of()),
      null,
      null,
      Map.of("sandbox", "service-secret")
    );
    when(catalog.service("service")).thenReturn(service);
    final var env = Json.read(
      "{\"displayName\":\"Sandbox\",\"clusterIdentity\":\"cluster\",\"monitoring\":{\"connectionRef\":\"lower\"}}",
      Catalog.Environment.class
    );
    when(catalog.environment("sandbox")).thenReturn(env);
    // Only metadata is needed here; use a direct data record to avoid reading Spring YAML as a connection document.
    final var credential = new ConnectionConfig.Credential(
      "environment",
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      "TOKEN"
    );
    final var data = new ConnectionConfig.Data(
      Map.of(),
      Map.of(),
      Map.of("service-secret", credential, "other-secret", credential),
      Map.of(),
      Map.of(),
      Map.of("lower", new ConnectionConfig.Loki("https://logs.invalid/loki/api/v1"))
    );
    when(connections.data()).thenReturn(data);
    when(credentials.resolve("service-secret")).thenReturn(
      new CredentialResolver.Secret(null, "service-token", true)
    );
    when(credentials.resolve("other-secret")).thenReturn(
      new CredentialResolver.Secret("reader", "password")
    );
    monitoring = new RunMonitoring(store, db, catalog, connections, credentials, http);
  }

  void panels(final RealPreparation.Metric... panels) {
    plan = Json.read(
      Json.write(
        Map.of(
          "actor",
          "alice",
          "profile",
          Map.of("targetEnvironment", "sandbox"),
          "effectiveLoadConfiguration",
          Map.of("metrics", List.of(panels), "loadService", "load"),
          "services",
          List.of(
            Map.of(
              "serviceId",
              "load",
              "effectiveValues",
              Map.of("generator", Map.of("runTIme", "PT72H"))
            )
          )
        )
      ),
      Plan.class
    );
    when(store.plan("plan")).thenReturn(plan);
  }

  /**
   * <b>Scenario:</b> Uses Panel Namespace And Mapped Secret For Each Query
   * <pre>
   * GIVEN ... monitoring panels using different namespaces and mapped credentials
   * WHEN ... each panel is queried
   * THEN ... its namespace and credential mapping are used independently
   * </pre>
   */
  @Test
  @DisplayName("Uses Panel Namespace And Mapped Secret For Each Query")
  void usesPanelNamespaceAndMappedSecretForEachQuery() {
    panels(
      new RealPreparation.Metric(
        "service",
        "rate",
        "sum(rate({namespace=\"{{namespace}}\"}[1m]))",
        "Rate",
        "grafana-ns",
        "metric",
        null
      ),
      new RealPreparation.Metric(
        "service",
        "logs",
        "{namespace=\"{{namespace}}\"}",
        "Logs",
        "other-ns",
        "logs",
        "other-secret"
      )
    );
    when(http.get(any(), any(), any())).thenReturn(
      new ReadOnlyHttp.Response(
        200,
        Map.of(),
        "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}".getBytes(
          StandardCharsets.UTF_8
        )
      )
    );
    monitoring.query("run", 0, "2026-09-30T10:00:00Z", "2026-09-30T10:15:00Z");
    verify(http).get(
      argThat((final var uri) ->
        URLDecoder.decode(uri.toString(), StandardCharsets.UTF_8).contains(
          "namespace=\"grafana-ns\""
        )
      ),
      eq("Bearer service-token"),
      eq("application/json")
    );
    monitoring.query("run", 1, "2026-09-30T10:00:00Z", "2026-09-30T10:15:00Z");
    verify(http).get(
      argThat((final var uri) ->
        URLDecoder.decode(uri.toString(), StandardCharsets.UTF_8).contains("namespace=\"other-ns\"")
      ),
      eq(RequestAuthentication.basic("reader", "password")),
      eq("application/json")
    );
    verify(credentials).resolve("other-secret");
  }

  /**
   * <b>Scenario:</b> Rejects Oversized Windows Before Resolving Credentials
   * <pre>
   * GIVEN ... a monitoring request exceeding the allowed query window
   * WHEN ... the query is submitted
   * THEN ... the request is rejected before any credential resolution
   * </pre>
   */
  @Test
  @DisplayName("Rejects Oversized Windows Before Resolving Credentials")
  void rejectsOversizedWindowsBeforeResolvingCredentials() {
    panels(new RealPreparation.Metric("service", "logs", "{namespace=\"{{namespace}}\"}"));
    assertThatThrownBy(() ->
      monitoring.query("run", 0, "2026-09-01T00:00:00Z", "2026-09-30T00:00:00Z")
    ).hasMessageContaining("7 days");
    verifyNoInteractions(credentials, http);
  }

  /**
   * <b>Scenario:</b> Dashboard Edits Persist Without Changing Prepared Verdict Queries
   * <pre>
   * GIVEN ... a prepared run with monitoring and verdict queries
   * WHEN ... dashboard panels are edited and reloaded
   * THEN ... edits persist without changing the prepared verdict queries
   * </pre>
   */
  @Test
  @DisplayName("Dashboard Edits Persist Without Changing Prepared Verdict Queries")
  void dashboardEditsPersistWithoutChangingPreparedVerdictQueries() {
    panels(
      new RealPreparation.Metric(
        "service",
        "count",
        "sum(count_over_time({namespace=\"{{namespace}}\"}[1m]))"
      )
    );
    final var jdbc = new JdbcTemplate(
      new org.springframework.jdbc.datasource.DriverManagerDataSource(
        "jdbc:h2:mem:monitor-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
        "sa",
        ""
      )
    );
    jdbc.execute("CREATE TABLE run_monitoring (run_id VARCHAR(64) PRIMARY KEY,body CLOB NOT NULL)");
    monitoring = new RunMonitoring(store, jdbc, catalog, connections, credentials, http);
    final var replacement = new RealPreparation.Metric(
      "service",
      "logs",
      "{namespace=\"{{namespace}}\"}",
      "New panel",
      "custom-ns",
      "logs",
      "other-secret"
    );
    monitoring.save("run", List.of(replacement));
    assertThat(monitoring.panels("run")).containsExactly(replacement);
    assertThat(Json.write(plan.effectiveLoadConfiguration()))
      .contains("count_over_time")
      .doesNotContain("custom-ns");
    verify(store).audit("MONITORING_UPDATED", "run");
    verifyNoInteractions(http, credentials);
  }

  /**
   * <b>Scenario:</b> Load YAML Duration Controls Only Monitoring Window
   * <pre>
   * GIVEN ... load-generator YAML defining a run duration
   * WHEN ... monitoring defaults are derived from the YAML
   * THEN ... the duration affects the monitoring window without changing execution timing
   * </pre>
   */
  @Test
  @DisplayName("Load Yaml Duration Controls Only Monitoring Window")
  void loadYamlDurationControlsOnlyMonitoringWindow() {
    panels();
    final var result = Json.MAPPER.valueToTree(monitoring.view("run"));
    assertThat(result.path("windowSeconds").asLong()).isEqualTo(259200);
    verifyNoInteractions(http, credentials);
  }
}

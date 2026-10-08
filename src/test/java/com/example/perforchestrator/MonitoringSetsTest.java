package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.perforchestrator.application.*;
import com.example.perforchestrator.domain.Model.Threshold;
import com.example.perforchestrator.infrastructure.config.*;
import com.example.perforchestrator.infrastructure.registry.ConnectionConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class MonitoringSetsTest {

  @TempDir
  Path directory;

  HikariDataSource db;
  final Catalog catalog = mock(Catalog.class);
  final ConnectionConfig connections = new ConnectionConfig(
    new ConnectionConfig.Data(Map.of(), Map.of(), Map.of(), Map.of())
  );

  @BeforeEach
  void setup() {
    when(catalog.mode()).thenReturn("real");
    when(catalog.boundEnvironment()).thenReturn("sandbox");
    when(catalog.selectedEnvironment(any())).thenAnswer((final var call) ->
      call.getArgument(0) == null ? catalog.boundEnvironment() : call.getArgument(0)
    );
    open();
    Flyway.configure().dataSource(db).load().migrate();
  }

  void open() {
    db = new HikariDataSource();
    db.setJdbcUrl("jdbc:h2:file:" + directory.resolve("sets"));
    db.setUsername("sa");
    db.setPassword("");
  }

  MonitoringSets sets() {
    return new MonitoringSets(new JdbcTemplate(db), catalog, connections);
  }

  MonitoringSets.Definition definition() {
    return new MonitoringSets.Definition(
      "SMTP traffic",
      List.of(
        new RealPreparation.Metric(
          "smtp",
          "rate",
          "sum(rate({namespace=\"{{namespace}}\"}[1m]))",
          "Processed rate",
          "smtp-ns",
          "metric",
          null
        )
      ),
      List.of(new Threshold("rate", 100, true))
    );
  }

  @AfterEach
  void close() {
    db.close();
  }

  /**
   * <b>Scenario:</b> Persists Across Restart And Scopes Sets To Environment
   * <pre>
   * GIVEN ... a saved monitoring set belonging to sandbox
   * WHEN ... the store restarts and sets are listed, updated, or deleted across environments
   * THEN ... the set persists, environment boundaries hold, and stale revisions are rejected
   * </pre>
   */
  @Test
  @DisplayName("Persists Across Restart And Scopes Sets To Environment")
  void persistsAcrossRestartAndScopesSetsToEnvironment() {
    final var saved = sets().save(null, null, definition());
    db.close();
    open();
    assertThat(sets().list()).containsExactly(saved);
    when(catalog.boundEnvironment()).thenReturn("perf");
    assertThat(sets().list()).isEmpty();
    assertThatThrownBy(() ->
      sets().save(saved.id(), saved.revision(), definition())
    ).hasMessageContaining("changed");
    assertThatThrownBy(() -> sets().delete(saved.id(), saved.revision())).hasMessageContaining(
      "changed"
    );
    when(catalog.boundEnvironment()).thenReturn("sandbox");
    final var updated = sets().save(saved.id(), saved.revision(), definition());
    assertThat(updated.revision()).isEqualTo(2);
    assertThatThrownBy(() ->
      sets().save(saved.id(), saved.revision(), definition())
    ).hasMessageContaining("changed");
    assertThatThrownBy(() -> sets().delete(saved.id(), saved.revision())).hasMessageContaining(
      "changed"
    );
    sets().delete(updated.id(), updated.revision());
    assertThat(sets().list()).isEmpty();
  }

  /**
   * <b>Scenario:</b> Validates Panels Credential References And Rules Without Resolving Secrets
   * <pre>
   * GIVEN ... monitoring panels with invalid definitions, credential references, or rules
   * WHEN ... a monitoring set is validated
   * THEN ... invalid sets are rejected without resolving secret values
   * </pre>
   */
  @Test
  @DisplayName("Validates Panels Credential References And Rules Without Resolving Secrets")
  void validatesPanelsCredentialReferencesAndRulesWithoutResolvingSecrets() {
    assertThatThrownBy(() ->
      sets().save(null, null, new MonitoringSets.Definition("empty", List.of(), List.of()))
    ).hasMessageContaining("between 1 and 20");
    final var panel = definition().panels().getFirst();
    assertThatThrownBy(() ->
      sets().save(
        null,
        null,
        new MonitoringSets.Definition("duplicate", List.of(panel, panel), List.of())
      )
    ).hasMessageContaining("unique");
    assertThatThrownBy(() ->
      sets().save(
        null,
        null,
        new MonitoringSets.Definition(
          "bad rule",
          List.of(panel),
          List.of(new Threshold("unknown", 1, true))
        )
      )
    ).hasMessageContaining("metric panel");
    final var invalid = new RealPreparation.Metric(
      "smtp",
      "rate",
      "query",
      "title",
      "ns",
      "metric",
      "unknown-secret"
    );
    assertThatThrownBy(() ->
      sets().save(null, null, new MonitoringSets.Definition("bad ref", List.of(invalid), List.of()))
    ).hasMessageContaining("configured credential");
    assertThat(sets().list()).isEmpty();
  }
}

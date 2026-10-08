package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.*;

import com.example.perforchestrator.infrastructure.config.Json;
import com.example.perforchestrator.infrastructure.diagnostics.*;
import com.example.perforchestrator.infrastructure.execution.*;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class DiagnosticLogTest {

  @TempDir
  Path directory;

  HikariDataSource db;
  DiagnosticLog log;

  @BeforeEach
  void setup() {
    open();
    Flyway.configure().dataSource(db).load().migrate();
  }

  void open() {
    db = new HikariDataSource();
    db.setJdbcUrl("jdbc:h2:file:" + directory.resolve("diagnostics"));
    db.setUsername("sa");
    db.setPassword("");
    log = new DiagnosticLog(new JdbcTemplate(db));
  }

  @AfterEach
  void close() {
    db.close();
  }

  /**
   * <b>Scenario:</b> Persists Preparation And Separates Run Activity Across Restart
   * <pre>
   * GIVEN ... preparation diagnostics and activity from separate runs
   * WHEN ... diagnostics are stored and the database is reopened
   * THEN ... preparation history persists and run activity remains isolated
   * </pre>
   */
  @Test
  @DisplayName("Persists Preparation And Separates Run Activity Across Restart")
  void persistsPreparationAndSeparatesRunActivityAcrossRestart() throws Exception {
    final String preparation = log.create(null);
    try (var scope = log.scope(preparation)) {
      new CommandRunner().run(
        List.of("/usr/bin/printf", "%s", "PRIVATE_OUTPUT"),
        directory,
        Map.of("TOKEN", "PRIVATE_ENV"),
        Duration.ofSeconds(2)
      );
    }
    log.plan(preparation, "plan");
    final String a = log.create(preparation),
      b = log.create(preparation);
    log.run(a, "run-a");
    log.run(b, "run-b");
    try (var scope = log.scope(a)) {
      DiagnosticLog.begin(
        "HTTP",
        SafeDiagnostics.endpoint(
          "GET",
          URI.create(
            "https://host.invalid/loki/api/v1/query?query=%7Bpassword%3D%22PRIVATE_QUERY%22%7D"
          )
        )
      ).finish("HTTP 403");
    }
    try (var scope = log.scope(b)) {
      DiagnosticLog.begin("COMMAND", "helm version").finish("EXIT 0");
    }
    DiagnosticLog.begin("COMMAND", "outside scope").finish("EXIT 0");
    db.close();
    open();
    assertThat(log.forRun("run-a")).isEqualTo(a);
    assertThat(log.forPlan("plan")).isEqualTo(preparation);
    final String first = Json.write(log.operations(a));
    final String second = Json.write(log.operations(b));
    assertThat(first)
      .contains("HTTP 403", "EXIT 0", "durationMs")
      .doesNotContain(
        "PRIVATE_OUTPUT",
        "PRIVATE_ENV",
        "PRIVATE_QUERY",
        "outside scope",
        "helm version"
      );
    assertThat(second).contains("helm version").doesNotContain("HTTP 403", "outside scope");
  }

  /**
   * <b>Scenario:</b> Nested Scopes Restore And Do Not Propagate To Unrelated Threads
   * <pre>
   * GIVEN ... nested diagnostic scopes and an unrelated thread
   * WHEN ... activity is recorded while entering and leaving the scopes
   * THEN ... the parent scope is restored and unrelated threads do not inherit it
   * </pre>
   */
  @Test
  @DisplayName("Nested Scopes Restore And Do Not Propagate To Unrelated Threads")
  void nestedScopesRestoreAndDoNotPropagateToUnrelatedThreads() throws Exception {
    final String a = log.create(null),
      b = log.create(null);
    try (var scope = log.scope(a)) {
      try (var inner = log.scope(b)) {
        DiagnosticLog.begin("COMMAND", "inner").finish("EXIT 0");
      }
      final var thread = new Thread(() ->
        DiagnosticLog.begin("COMMAND", "unrelated").finish("EXIT 0")
      );
      thread.start();
      thread.join();
      DiagnosticLog.begin("COMMAND", "outer").finish("EXIT 0");
    }
    assertThat(Json.write(log.operations(a)))
      .contains("outer")
      .doesNotContain("inner", "unrelated");
    assertThat(Json.write(log.operations(b)))
      .contains("inner")
      .doesNotContain("outer");
  }

  /**
   * <b>Scenario:</b> Parallel Scope Is Propagated And Cleared After Work
   * <pre>
   * GIVEN ... a diagnostic scope captured for parallel work
   * WHEN ... the worker records activity and finishes
   * THEN ... activity uses the captured scope and the worker scope is cleared afterward
   * </pre>
   */
  @Test
  @DisplayName("Parallel Scope Is Propagated And Cleared After Work")
  void parallelScopeIsPropagatedAndClearedAfterWork() throws Exception {
    final String id = log.create(null);
    try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      try (var scope = log.scope(id)) {
        pool
          .submit(
            DiagnosticLog.propagate(() -> {
              DiagnosticLog.begin("COMMAND", "parallel").finish("EXIT 0");
              return null;
            })
          )
          .get();
      }
      pool.submit(() -> DiagnosticLog.begin("COMMAND", "unrelated").finish("EXIT 0")).get();
    }
    assertThat(Json.write(log.operations(id)))
      .contains("parallel")
      .doesNotContain("unrelated");
  }

  /**
   * <b>Scenario:</b> Disabled Diagnostics Do Not Create Traces Or Record Activity
   * <pre>
   * GIVEN ... diagnostics disabled in configuration
   * WHEN ... trace creation and activity recording are attempted
   * THEN ... no traces or activity are stored
   * </pre>
   */
  @Test
  @DisplayName("Disabled Diagnostics Do Not Create Traces Or Record Activity")
  void disabledDiagnosticsDoNotCreateTracesOrRecordActivity() {
    final var jdbc = new JdbcTemplate(db);
    final var disabled = new DiagnosticLog(jdbc, false);
    assertThat(disabled.enabled()).isFalse();
    assertThat(disabled.create(null)).isNull();
    try (var scope = disabled.scope(null)) {
      DiagnosticLog.begin("COMMAND", "helm version").finish("EXIT 0");
    }
    disabled.plan(null, "plan");
    disabled.run(null, "run");
    assertThat(disabled.recent()).isEmpty();
    assertThat(disabled.operations("any")).isEqualTo(List.of());
    assertThat(
      jdbc.queryForObject("SELECT COUNT(*) FROM diagnostic_traces", Integer.class)
    ).isZero();
    assertThat(
      jdbc.queryForObject("SELECT COUNT(*) FROM diagnostic_operations", Integer.class)
    ).isZero();
  }

  /**
   * <b>Scenario:</b> Operational Details Remain Visible While Credential Values Are Masked
   * <pre>
   * GIVEN ... command and API details containing credentials
   * WHEN ... diagnostic redaction is applied
   * THEN ... operational details remain visible while credential values are masked
   * </pre>
   */
  @Test
  @DisplayName("Operational Details Remain Visible While Credential Values Are Masked")
  void operationalDetailsRemainVisibleWhileCredentialValuesAreMasked() {
    final String command = SafeDiagnostics.command(
      List.of(
        "helm",
        "repo",
        "add",
        "private-alias",
        "https://u:PRIVATE@host.invalid",
        "--password",
        "PRIVATE",
        "--username",
        "first.last",
        "--set=token=PRIVATE"
      )
    );
    assertThat(command)
      .contains("helm repo add", "--username first.last", "private-alias", "[redacted]")
      .doesNotContain("PRIVATE", "u:");
    assertThat(
      SafeDiagnostics.command(
        List.of(
          "helm",
          "--kube-context",
          "sandbox-nvan",
          "list",
          "--namespace",
          "sng-smtp-receiver",
          "--output",
          "json"
        )
      )
    ).isEqualTo(
      "helm --kube-context sandbox-nvan list --namespace sng-smtp-receiver --output json"
    );
    assertThat(
      SafeDiagnostics.command(
        List.of(
          "git",
          "-c",
          "http.extraHeader=Authorization: Bearer PRIVATE",
          "fetch",
          "origin",
          "master"
        )
      )
    )
      .contains("fetch origin master")
      .doesNotContain("PRIVATE");
    assertThat(
      SafeDiagnostics.command(
        List.of("helm", "--header", "Cookie: session=PRIVATE; csrf=PRIVATE", "--password=PRIVATE")
      )
    ).doesNotContain("PRIVATE");
    final String endpoint = SafeDiagnostics.endpoint(
      "GET",
      URI.create(
        "https://u:PRIVATE@host.invalid/SecretServer/api/v1/secrets/20000?token=PRIVATE&start=10"
      )
    );
    assertThat(endpoint)
      .contains("host.invalid/SecretServer/api/v1/secrets/20000", "start=10")
      .doesNotContain("PRIVATE", "u:");
    assertThat(
      ClusterDiagnostics.pluginHint(
        "getting credentials: exec: executable /private/bin/kubelogin not found PRIVATE_TOKEN"
      )
    )
      .contains("kubelogin", "PATH")
      .doesNotContain("/private/", "PRIVATE_TOKEN");
    assertThat(
      ClusterDiagnostics.pluginHint("exec: executable helper failed with exit code 1 PRIVATE_TOKEN")
    )
      .contains("helper exited with code 1")
      .doesNotContain("PRIVATE_TOKEN");
  }
}

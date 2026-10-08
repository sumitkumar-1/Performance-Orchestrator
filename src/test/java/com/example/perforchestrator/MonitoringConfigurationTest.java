package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.*;

import com.example.perforchestrator.infrastructure.config.*;
import com.example.perforchestrator.infrastructure.registry.ConnectionConfig;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MonitoringConfigurationTest {

  /**
   * <b>Scenario:</b> Migrates Shared Urls And Matching Namespaces Without Discarding Distinct Metric Settings
   * <pre>
   * GIVEN ... legacy environments sharing a Loki URL but containing distinct metric settings
   * WHEN ... configuration is migrated to shared monitoring connections
   * THEN ... shared URLs and namespace secrets are normalized while distinct metric settings survive
   * </pre>
   */
  @Test
  @DisplayName(
    "Migrates Shared Urls And Matching Namespaces Without Discarding Distinct Metric Settings"
  )
  void migratesSharedUrlsAndMatchingNamespacesWithoutDiscardingDistinctMetricSettings() {
    final var same = new Catalog.NamespaceCredentials("sandbox-secret", "sandbox-secret");
    final var different = new Catalog.NamespaceCredentials("qa-logs", "qa-metrics");
    final var environments = Map.of(
      "sandbox",
      new Catalog.Environment(
        "Sandbox",
        "cluster",
        null,
        null,
        null,
        null,
        null,
        new Catalog.Monitoring("https://loki.invalid/loki/api/v1", null, Map.of("smtp", same))
      ),
      "qa",
      new Catalog.Environment(
        "QA",
        "cluster",
        null,
        null,
        null,
        null,
        null,
        new Catalog.Monitoring(
          "https://loki.invalid/loki/api/v1",
          "https://prometheus.invalid/api/v1",
          Map.of("smtp", different)
        )
      )
    );
    final var service = new Catalog.Service(
      "projects/smtp",
      List.of(),
      Map.of(),
      null,
      List.of(),
      new Catalog.Destination("smtp", "smtp", List.of("values.yaml"))
    );
    final var document = new RuntimeConfiguration.Document(
      "v1",
      new Catalog.Data("real", environments, Map.of("smtp", service), Map.of(), Map.of()),
      new ConnectionConfig.Data(Map.of(), Map.of(), Map.of(), Map.of())
    );
    assertThat(document.connections().loki()).hasSize(1);
    final var sandbox = document.catalog().environments().get("sandbox").monitoring();
    final var qa = document.catalog().environments().get("qa").monitoring();
    assertThat(sandbox.connectionRef()).isEqualTo(qa.connectionRef());
    assertThat(sandbox.logsApiBaseUrl()).isNull();
    assertThat(sandbox.namespaceCredentials()).isNull();
    assertThat(document.catalog().services().get("smtp").monitoringCredentials())
      .containsEntry("sandbox", "sandbox-secret")
      .containsEntry("qa", "qa-logs");
    assertThat(qa.metricsApiBaseUrl()).isEqualTo("https://prometheus.invalid/api/v1");
    assertThat(qa.namespaceCredentials().get("smtp").metricsCredentialRef()).isEqualTo(
      "qa-metrics"
    );
    assertThat(Json.read(Json.write(document), RuntimeConfiguration.Document.class)).isEqualTo(
      document
    );
  }
}

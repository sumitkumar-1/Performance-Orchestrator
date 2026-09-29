package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.*;
import com.example.perforchestrator.infrastructure.config.*;
import com.example.perforchestrator.infrastructure.registry.ConnectionConfig;
import java.util.*;
import org.junit.jupiter.api.Test;

class MonitoringConfigurationTest {
  @Test void migratesSharedUrlsAndMatchingNamespacesWithoutDiscardingDistinctMetricSettings() {
    var same = new Catalog.NamespaceCredentials("sandbox-secret", "sandbox-secret");
    var different = new Catalog.NamespaceCredentials("qa-logs", "qa-metrics");
    var environments = Map.of(
        "sandbox", new Catalog.Environment("Sandbox", "cluster", null, null, null, null, null,
            new Catalog.Monitoring("https://loki.invalid/loki/api/v1", null, Map.of("smtp", same))),
        "qa", new Catalog.Environment("QA", "cluster", null, null, null, null, null,
            new Catalog.Monitoring("https://loki.invalid/loki/api/v1", "https://prometheus.invalid/api/v1", Map.of("smtp", different))));
    var service = new Catalog.Service("projects/smtp", List.of(), Map.of(), null, List.of(), new Catalog.Destination("smtp", "smtp", List.of("values.yaml")));
    var document = new RuntimeConfiguration.Document("v1", new Catalog.Data("real", environments, Map.of("smtp", service), Map.of(), Map.of()), new ConnectionConfig.Data(Map.of(), Map.of(), Map.of(), Map.of()));
    assertThat(document.connections().loki()).hasSize(1);
    var sandbox = document.catalog().environments().get("sandbox").monitoring();
    var qa = document.catalog().environments().get("qa").monitoring();
    assertThat(sandbox.connectionRef()).isEqualTo(qa.connectionRef());
    assertThat(sandbox.logsApiBaseUrl()).isNull(); assertThat(sandbox.namespaceCredentials()).isNull();
    assertThat(document.catalog().services().get("smtp").monitoringCredentials()).containsEntry("sandbox", "sandbox-secret").containsEntry("qa", "qa-logs");
    assertThat(qa.metricsApiBaseUrl()).isEqualTo("https://prometheus.invalid/api/v1");
    assertThat(qa.namespaceCredentials().get("smtp").metricsCredentialRef()).isEqualTo("qa-metrics");
    assertThat(Json.read(Json.write(document), RuntimeConfiguration.Document.class)).isEqualTo(document);
  }
}

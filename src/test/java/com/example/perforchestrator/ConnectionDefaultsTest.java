package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.perforchestrator.infrastructure.registry.ConnectionConfig;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ConnectionDefaultsTest {
  @Test
  void realCatalogBindsStartupMapsWithoutMockData() throws Exception {
    var environment = new MockEnvironment()
        .withProperty("orchestrator.target-environment", "dev")
        .withProperty("orchestrator.catalog-defaults.environments.dev.display-name", "Dev")
        .withProperty("orchestrator.catalog-defaults.environments.dev.cluster-identity", "dev")
        .withProperty("orchestrator.catalog-defaults.environments.dev.service-namespaces[0]", "smtp")
        .withProperty("orchestrator.catalog-defaults.environments.dev.load-generator-namespace", "load")
        .withProperty("orchestrator.catalog-defaults.environments.dev.allowed-actions[0]", "PLAN")
        .withProperty("orchestrator.catalog-defaults.environments.dev.limits.max-run-duration-seconds", "300")
        .withProperty("orchestrator.catalog-defaults.environments.dev.limits.max-virtual-users", "1")
        .withProperty("orchestrator.catalog-defaults.environments.dev.limits.max-requests-per-second", "10");
    var catalog = new com.example.perforchestrator.infrastructure.config.Catalog(
        "", "real", "127.0.0.1", environment);
    assertThat(catalog.environment("dev").displayName()).isEqualTo("Dev");
    assertThat(catalog.data().services()).isEmpty();
    assertThat(catalog.data().mode()).isEqualTo("real");
  }

  @Test
  void springPropertiesBindConnectionsAndExplicitFileTakesPrecedence() throws Exception {
    var environment = new MockEnvironment()
        .withProperty("orchestrator.connection-defaults.secret-servers.office.api-base-url",
            "https://vault.example.invalid/SecretServer/api/v1")
        .withProperty("orchestrator.connection-defaults.secret-servers.office.auth-mode", "portal")
        .withProperty("orchestrator.connection-defaults.secret-servers.office.token-url",
            "https://vault.example.invalid/SecretServer/oauth2/token");
    var bound = new ConnectionConfig("", environment);
    assertThat(bound.data().secretServers().get("office").mode()).isEqualTo("portal");
    var external = new ConnectionConfig("docs/integration/examples/office-pilot-connections.yaml", environment);
    assertThat(external.data().secretServers()).containsKey("office-vault").doesNotContainKey("office");
  }
}

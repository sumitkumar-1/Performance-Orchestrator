package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.perforchestrator.infrastructure.registry.ConnectionConfig;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ConnectionDefaultsTest {
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

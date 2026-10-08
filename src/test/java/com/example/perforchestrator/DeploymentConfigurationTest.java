package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DeploymentConfigurationTest {

  /**
   * <b>Scenario:</b> Every Real Environment Has Connections And Service Image Defaults
   * <pre>
   * GIVEN ... packaged defaults for all real environments
   * WHEN ... deployment configuration is loaded
   * THEN ... environments retain valid connection references and service image defaults
   * </pre>
   */
  @Test
  @DisplayName("Every Real Environment Has Connections And Service Image Defaults")
  void everyRealEnvironmentHasConnectionsAndServiceImageDefaults() {
    for (final String target : new String[] { "sandbox", "dev", "qa", "stable", "perf", "perf3" }) {
      new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withPropertyValues(
          "spring.config.location=classpath:application-test.yaml",
          "orchestrator.mode=real",
          "orchestrator.target-environment=" + target
        )
        .run((final var context) -> {
          final var environment = context.getEnvironment();
          final var catalog = new com.example.perforchestrator.infrastructure.config.Catalog(
            environment.getProperty("orchestrator.catalog"),
            "real",
            "127.0.0.1",
            environment
          );
          final var connections =
            new com.example.perforchestrator.infrastructure.registry.ConnectionConfig(
              "",
              environment
            );
          assertThat(catalog.data().environments()).containsKeys(
            "sandbox",
            "dev",
            "qa",
            "stable",
            "perf",
            "perf3"
          );
          assertThat(catalog.data().services()).isNotEmpty();
          assertThat(catalog.data().scenarios()).isNotEmpty();
          assertThat(connections.data().artifactory()).isNotEmpty();
          assertThat(connections.data().secretServers()).isNotEmpty();
          assertThat(connections.data().credentials()).isNotEmpty();
          assertThat(connections.data().bitbucket()).isNotEmpty();
          assertThat(
            catalog.data().services().get("ps-spoolers-ps-load-gen").containerImage().repoStage()
          ).isEqualTo("dev");
          final var monitoring = catalog.environment(target).monitoring();
          assertThat(connections.data().loki()).containsKey(monitoring.connectionRef());
          assertThat(monitoring.logsApiBaseUrl()).isNull();
          assertThat(monitoring.metricsApiBaseUrl()).isNull();
          assertThat(monitoring.namespaceCredentials()).isNull();
          assertThat(connections.data().credentials()).containsKey(
            catalog
              .data()
              .services()
              .get("ps-spoolers-ps-load-gen")
              .monitoringCredentials()
              .get(target)
          );
        });
    }
  }

  /**
   * <b>Scenario:</b> Test Configuration Uses Isolated In Memory Databases Without Requiring Secrets
   * <pre>
   * GIVEN ... the dedicated test configuration
   * WHEN ... test application settings are loaded
   * THEN ... databases are isolated in memory and external secrets are not required
   * </pre>
   */
  @Test
  @DisplayName("Test Configuration Uses Isolated In Memory Databases Without Requiring Secrets")
  void testConfigurationUsesIsolatedInMemoryDatabasesWithoutRequiringSecrets() {
    for (final String mode : new String[] { "simulation", "real" }) {
      new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withPropertyValues(
          "spring.config.location=classpath:application-test.yaml",
          "orchestrator.mode=" + mode
        )
        .run((final var context) -> {
          assertThat(context).hasNotFailed();
          final var environment = context.getEnvironment();
          assertThat(environment.getActiveProfiles()).containsExactly(mode);
          assertThat(environment.getProperty("spring.datasource.password")).isEmpty();
          assertThat(environment.getProperty("spring.config.import")).isNull();
          assertThat(environment.getProperty("spring.datasource.url")).isEqualTo(
            "jdbc:h2:mem:test-" + mode + ";DB_CLOSE_DELAY=-1"
          );
        });
    }
  }
}

package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DeploymentConfigurationTest {
  @Test
  void everyRealEnvironmentHasConnectionsAndServiceImageDefaults() {
    for (String target : new String[] {"sandbox", "dev", "qa", "stable", "perf", "perf3"}) {
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withPropertyValues("spring.config.location=classpath:application-test.yaml", "orchestrator.mode=real", "orchestrator.target-environment=" + target)
          .run(context -> {
            var environment = context.getEnvironment();
            var catalog = new com.example.perforchestrator.infrastructure.config.Catalog(
                environment.getProperty("orchestrator.catalog"), "real", "127.0.0.1", environment);
            var connections = new com.example.perforchestrator.infrastructure.registry.ConnectionConfig("", environment);
            assertThat(catalog.data().environments()).containsOnlyKeys(target);
            assertThat(catalog.data().services()).isNotEmpty();
            assertThat(catalog.data().scenarios()).isNotEmpty();
            assertThat(connections.data().artifactory()).isNotEmpty();
            assertThat(connections.data().secretServers()).isNotEmpty();
            assertThat(connections.data().credentials()).isNotEmpty();
            assertThat(connections.data().bitbucket()).isNotEmpty();
            assertThat(catalog.data().services().get("ps-spoolers-ps-load-gen").containerImage().repoStage()).isEqualTo("dev");
            var monitoring = catalog.environment(target).monitoring();
            assertThat(connections.data().loki()).containsKey(monitoring.connectionRef());
            assertThat(monitoring.logsApiBaseUrl()).isNull();
            assertThat(monitoring.metricsApiBaseUrl()).isNull();
            assertThat(monitoring.namespaceCredentials()).isNull();
            assertThat(connections.data().credentials()).containsKey(catalog.data().services().get("ps-spoolers-ps-load-gen").monitoringCredentials().get(target));
          });
    }
  }

  @Test
  void testConfigurationUsesIsolatedInMemoryDatabasesWithoutRequiringSecrets() {
    for (String mode : new String[] {"simulation", "real"}) {
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withPropertyValues("spring.config.location=classpath:application-test.yaml", "orchestrator.mode=" + mode)
          .run(context -> {
            assertThat(context).hasNotFailed();
            var environment = context.getEnvironment();
            assertThat(environment.getActiveProfiles()).containsExactly(mode);
            assertThat(environment.getProperty("spring.datasource.password")).isEmpty();
            assertThat(environment.getProperty("spring.config.import")).isNull();
            assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:h2:mem:test-" + mode + ";DB_CLOSE_DELAY=-1");
          });
    }
  }
}

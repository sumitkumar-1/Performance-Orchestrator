package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DeploymentConfigurationTest {
  @Test
  void sharedConfigurationKeepsModeSpecificStateWithoutRequiringDatabaseSecrets() {
    for (String mode : new String[] {"simulation", "real"}) {
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withPropertyValues("orchestrator.mode=" + mode)
          .run(context -> {
            assertThat(context).hasNotFailed();
            var environment = context.getEnvironment();
            assertThat(environment.getActiveProfiles()).containsExactly(mode);
            assertThat(environment.getProperty("spring.datasource.password")).isEmpty();
            assertThat(environment.getProperty("spring.config.import")).isNull();
            assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:h2:file:./data/"
                    + (mode.equals("real") ? "real/" : "")
                    + "orchestrator;DB_CLOSE_ON_EXIT=FALSE");
          });
    }
  }
}

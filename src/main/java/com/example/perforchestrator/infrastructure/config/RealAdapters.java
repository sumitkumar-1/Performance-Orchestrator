package com.example.perforchestrator.infrastructure.config;

import com.example.perforchestrator.domain.*;
import com.example.perforchestrator.domain.Model.*;
import com.example.perforchestrator.infrastructure.registry.ArtifactoryImages;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

/** Real read-only adapters; unsupported execution must never use simulation implementations. */
@Configuration
@ConditionalOnProperty(name = "orchestrator.mode", havingValue = "real")
public class RealAdapters {
  private static Problem unavailable() {
    return new Problem(
        501,
        "REAL_EXECUTION_UNAVAILABLE",
        "mode",
        "Real CKP deployment and load-generator contracts are not configured");
  }

  @Bean
  Ports.ImageResolver realImages(ArtifactoryImages images) {
    return new Ports.ImageResolver() {
      public Image resolve(String service, Build build) {
        if (build == null)
          throw Problem.invalid("build", "Choose a registered image source and version");
        return images.resolve(service, build.sourceRef(), build.username(), build.version());
      }
    };
  }

  @Bean
  Ports.DeploymentGateway realDeployment() {
    return new Ports.DeploymentGateway() {
      public String baseline(String cluster, String namespace, String service) {
        throw unavailable();
      }

      public void deploy(String id, Plan plan, PreparedService service) {
        throw unavailable();
      }

      public boolean ready(Plan plan, PreparedService service) {
        throw unavailable();
      }
    };
  }

  @Bean
  Ports.LoadGeneratorGateway realLoadGenerator() {
    return new Ports.LoadGeneratorGateway() {
      public String start(String id, Plan plan) {
        throw unavailable();
      }

      public void stop(String id) {
        throw unavailable();
      }

      public boolean stopped(String id) {
        throw unavailable();
      }

      public Map<String, Double> collect(String id, Plan plan) {
        throw unavailable();
      }
    };
  }
}

package com.example.perforchestrator.application;

import static com.example.perforchestrator.domain.Model.*;

import com.example.perforchestrator.infrastructure.config.*;
import com.example.perforchestrator.infrastructure.persistence.Store;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "orchestrator.mode",
    havingValue = "simulation",
    matchIfMissing = true)
@Component
public class SeedData implements ApplicationRunner {
  private final Store store;
  private final PlanningService planning;

  public SeedData(Store store, PlanningService planning, RuntimeConfiguration configuration) {
    this.store = store;
    this.planning = planning;
  }

  public void run(ApplicationArguments args) {
    if (!store.profiles().isEmpty()) return;
    try {
      Profile profile =
          Json.read(ConfigurationResources.read("classpath:mocks/profile.json"), Profile.class);
      // A customized catalog may intentionally remove the bundled example services.
      try {
        planning.validate(profile);
      } catch (com.example.perforchestrator.domain.Problem ignored) {
        return;
      }
      planning.save(null, null, profile);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Missing mock profile resource", e);
    }
  }
}

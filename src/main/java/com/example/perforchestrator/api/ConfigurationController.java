package com.example.perforchestrator.api;

import com.example.perforchestrator.infrastructure.config.RuntimeConfiguration;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/configuration")
public class ConfigurationController {
  private final RuntimeConfiguration configuration;

  private final com.example.perforchestrator.infrastructure.persistence.Store store;
  public ConfigurationController(RuntimeConfiguration configuration, com.example.perforchestrator.infrastructure.persistence.Store store) {
    this.store=store;
    this.configuration = configuration;
  }

  @GetMapping
  public Object get() {
    return configuration.current();
  }

  @PutMapping
  public Object update(@RequestBody RuntimeConfiguration.Document document) {
    String actor=com.example.perforchestrator.infrastructure.secrets.SecretServerTokens.currentActor();
    var result=configuration.update(document);
    store.audit(actor,"CONFIGURATION_UPDATED",result.revision());
    return result;
  }

  @GetMapping("/startup")
  public Object startup() {
    return configuration.startup();
  }
}

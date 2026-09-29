package com.example.perforchestrator.api;

import com.example.perforchestrator.infrastructure.config.RuntimeConfiguration;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/configuration")
public class ConfigurationController {
  private final RuntimeConfiguration configuration;

  public ConfigurationController(RuntimeConfiguration configuration) {
    this.configuration = configuration;
  }

  @GetMapping
  public Object get() {
    return configuration.current();
  }

  @PutMapping
  public Object update(@RequestBody RuntimeConfiguration.Document document) {
    return configuration.update(document);
  }
}

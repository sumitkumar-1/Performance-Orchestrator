package com.example.perforchestrator.api;

import com.example.perforchestrator.application.MonitoringSets;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/execution/monitoring-sets")
public class MonitoringSetsController {

  private final MonitoringSets sets;

  public MonitoringSetsController(final MonitoringSets sets) {
    this.sets = sets;
  }

  @GetMapping
  public Object list(final @RequestParam(required = false) String environment) {
    return sets.list(environment);
  }

  public record Input(String id, Integer revision, MonitoringSets.Definition definition) {}

  @PostMapping
  public Object save(
    final @RequestBody Input input,
    final @RequestParam(required = false) String environment
  ) {
    return sets.save(input.id(), input.revision(), input.definition(), environment);
  }

  @DeleteMapping("/{id}")
  public Object delete(
    final @PathVariable String id,
    final @RequestParam int revision,
    final @RequestParam(required = false) String environment
  ) {
    sets.delete(id, revision, environment);
    return java.util.Map.of("deleted", true);
  }
}

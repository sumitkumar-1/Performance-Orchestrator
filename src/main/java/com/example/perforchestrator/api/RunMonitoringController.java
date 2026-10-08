package com.example.perforchestrator.api;

import com.example.perforchestrator.application.*;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/execution/runs/{id}/monitoring")
public class RunMonitoringController {

  private final com.example.perforchestrator.infrastructure.diagnostics.DiagnosticLog diagnostics;
  private final RunMonitoring monitoring;

  public RunMonitoringController(
    final RunMonitoring monitoring,
    final com.example.perforchestrator.infrastructure.diagnostics.DiagnosticLog diagnostics
  ) {
    this.diagnostics = diagnostics;
    this.monitoring = monitoring;
  }

  @GetMapping
  public Object view(final @PathVariable String id) {
    return monitoring.view(id);
  }

  public record Panels(List<RunPreparation.Metric> panels) {}

  @PutMapping
  public Object save(final @PathVariable String id, final @RequestBody Panels input) {
    return monitoring.save(id, input.panels());
  }

  public record Range(int panel, String start, String end) {}

  @PostMapping("/query")
  public Object query(final @PathVariable String id, final @RequestBody Range range) {
    try (var scope = diagnostics.scope(diagnostics.forRun(id))) {
      return monitoring.query(id, range.panel(), range.start(), range.end());
    }
  }
}

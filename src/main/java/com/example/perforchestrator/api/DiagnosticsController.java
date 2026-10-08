package com.example.perforchestrator.api;

import com.example.perforchestrator.infrastructure.diagnostics.DiagnosticLog;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/real")
public class DiagnosticsController {

  private final DiagnosticLog log;

  public DiagnosticsController(final DiagnosticLog log) {
    this.log = log;
  }

  @GetMapping("/diagnostics/settings")
  public Object settings() {
    return Map.of("enabled", log.enabled());
  }

  @PostMapping("/diagnostics")
  public Object create() {
    return log.enabled()
      ? Map.of("id", log.create(null), "enabled", true)
      : Map.of("enabled", false);
  }

  @GetMapping("/diagnostics")
  public Object recent() {
    return log.recent();
  }

  @GetMapping("/diagnostics/{id}")
  public Object operations(final @PathVariable String id) {
    return log.operations(id);
  }

  @GetMapping("/runs/{id}/diagnostics")
  public Object run(final @PathVariable String id) {
    final String trace = log.forRun(id);
    return trace == null ? List.of() : log.operations(trace);
  }
}

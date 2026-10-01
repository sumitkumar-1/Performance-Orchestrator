package com.example.perforchestrator.api;

import com.example.perforchestrator.infrastructure.diagnostics.DiagnosticLog;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/real")
public class DiagnosticsController {
  private final DiagnosticLog log;
  public DiagnosticsController(DiagnosticLog log){this.log=log;}
  @PostMapping("/diagnostics") public Object create(){return Map.of("id",log.create(null));}
  @GetMapping("/diagnostics") public Object recent(){return log.recent();}
  @GetMapping("/diagnostics/{id}") public Object operations(@PathVariable String id){return log.operations(id);}
  @GetMapping("/runs/{id}/diagnostics") public Object run(@PathVariable String id){String trace=log.forRun(id);return trace==null?List.of():log.operations(trace);}
}

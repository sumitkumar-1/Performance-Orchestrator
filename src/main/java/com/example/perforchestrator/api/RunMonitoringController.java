package com.example.perforchestrator.api;
import com.example.perforchestrator.application.*;
import java.util.List;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/real/runs/{id}/monitoring")
public class RunMonitoringController {
  private final RunMonitoring monitoring;
  public RunMonitoringController(RunMonitoring monitoring){this.monitoring=monitoring;}
  @GetMapping public Object view(@PathVariable String id){return monitoring.view(id);}
  public record Panels(List<RealPreparation.Metric> panels){}
  @PutMapping public Object save(@PathVariable String id,@RequestBody Panels input){return monitoring.save(id,input.panels());}
  public record Range(int panel,String start,String end){}
  @PostMapping("/query") public Object query(@PathVariable String id,@RequestBody Range range){return monitoring.query(id,range.panel(),range.start(),range.end());}
}

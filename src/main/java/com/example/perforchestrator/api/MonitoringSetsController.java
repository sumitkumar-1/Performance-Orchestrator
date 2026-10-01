package com.example.perforchestrator.api;

import com.example.perforchestrator.application.MonitoringSets;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/real/monitoring-sets")
public class MonitoringSetsController {
  private final MonitoringSets sets;
  public MonitoringSetsController(MonitoringSets sets){this.sets=sets;}
  @GetMapping public Object list(){return sets.list();}
  public record Input(String id,Integer revision,MonitoringSets.Definition definition){}
  @PostMapping public Object save(@RequestBody Input input){return sets.save(input.id(),input.revision(),input.definition());}
  @DeleteMapping("/{id}") public Object delete(@PathVariable String id,@RequestParam int revision){sets.delete(id,revision);return java.util.Map.of("deleted",true);}
}

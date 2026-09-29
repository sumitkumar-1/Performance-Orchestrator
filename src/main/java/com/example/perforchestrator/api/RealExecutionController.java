package com.example.perforchestrator.api;

import com.example.perforchestrator.application.*;
import com.example.perforchestrator.infrastructure.execution.ExecutionSettings;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/real")
public class RealExecutionController {
  private final RealPreparation preparation;private final RealRuns runs;private final ExecutionSettings settings;private final RealProfiles profiles;
  public RealExecutionController(RealPreparation preparation,RealRuns runs,ExecutionSettings settings,RealProfiles profiles){this.profiles=profiles;this.preparation=preparation;this.runs=runs;this.settings=settings;}
  @GetMapping("/execution") public Object settings(){return Map.of("enabled",settings.enabled,"kubeContext",settings.context,"expectedApiServer",settings.expectedServer,"commandTimeoutSeconds",settings.timeoutSeconds);}
  @GetMapping("/profiles") public Object profiles(){return profiles.list();}
  public record ProfileInput(String id,Integer revision,RealPreparation.Request profile){}
  @PostMapping("/profiles") public Object save(@RequestBody ProfileInput input){return profiles.save(input.id(),input.revision(),input.profile());}
  public record SourceRequest(String revision){}
  @PostMapping("/services/{service}/values") public Object values(@PathVariable String service,@RequestBody SourceRequest request){return preparation.profiles(service,request.revision());}
  @PostMapping("/plans") public Object prepare(@RequestBody RealPreparation.Request request){return preparation.prepare(request);}
  public record Submit(String planId){}
  @PostMapping("/runs") public Object run(@RequestHeader("Idempotency-Key")String key,@RequestBody Submit request){return runs.enqueue(key,request.planId());}
  @PostMapping("/runs/{id}/recover") public Object recover(@PathVariable String id){return runs.recover(id);}
}

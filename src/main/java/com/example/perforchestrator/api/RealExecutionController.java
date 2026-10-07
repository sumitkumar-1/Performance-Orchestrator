package com.example.perforchestrator.api;

import com.example.perforchestrator.application.*;
import com.example.perforchestrator.infrastructure.execution.ExecutionSettings;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/real")
public class RealExecutionController {
  @org.springframework.beans.factory.annotation.Autowired private com.example.perforchestrator.infrastructure.diagnostics.DiagnosticLog diagnostics;
  private final RealPreparation preparation;private final RealRuns runs;private final ExecutionSettings settings;private final RealProfiles profiles;
  public RealExecutionController(RealPreparation preparation,RealRuns runs,ExecutionSettings settings,RealProfiles profiles){this.profiles=profiles;this.preparation=preparation;this.runs=runs;this.settings=settings;}
  @GetMapping("/execution") public Object settings(){return Map.of("enabled",settings.enabled,"kubeContext",settings.context,"expectedApiServer",settings.expectedServer,"commandTimeoutSeconds",settings.timeoutSeconds,"defaultRunDurationSeconds",settings.defaultRunDurationSeconds,"maxRunDurationSeconds",settings.maxRunDurationSeconds);}
  @GetMapping("/profiles") public Object profiles(){return profiles.list();}
  public record ProfileInput(String id,Integer revision,RealPreparation.Request profile){}
  @PostMapping("/profiles") public Object save(@RequestBody ProfileInput input){return profiles.save(input.id(),input.revision(),input.profile());}
  public record SourceRequest(String revision){}
  @PostMapping("/services/{service}/values") public Object values(@PathVariable String service,@RequestBody SourceRequest request){return preparation.profiles(service,request.revision());}
  @GetMapping("/preparations/{id}") public Object progress(@PathVariable String id,jakarta.servlet.http.HttpServletRequest request){
    var session=request.getSession(false);
    var value=session==null?null:session.getAttribute("reviewProgress");
    if(!(value instanceof ReviewProgress review) || !review.id().equals(id))throw com.example.perforchestrator.domain.Problem.missing("Review progress");
    return review.snapshot();
  }
  @PostMapping("/plans") public Object prepare(@RequestBody RealPreparation.Request request,@RequestHeader(value="X-Diagnostic-ID",required=false) String trace,
      @RequestHeader(value="X-Review-ID",required=false) String reviewId,jakarta.servlet.http.HttpServletRequest servletRequest){
    if(reviewId!=null && !reviewId.matches("[a-zA-Z0-9-]{1,80}"))throw com.example.perforchestrator.domain.Problem.invalid("reviewId","Invalid review identifier");
    var progress=new ReviewProgress(reviewId==null?java.util.UUID.randomUUID().toString():reviewId,request);
    servletRequest.getSession().setAttribute("reviewProgress",progress);
    if(trace==null)trace=diagnostics.create(null);
    try(var scope=diagnostics.scope(trace)){var plan=preparation.prepare(request,progress::step);diagnostics.plan(trace,plan.id());progress.finish(true);return plan;}
    catch(RuntimeException error){progress.finish(false);throw error;}
  }
  public record Submit(String planId){}
  @PostMapping("/runs") public Object run(@RequestHeader("Idempotency-Key")String key,@RequestBody Submit request){
    String trace=diagnostics.create(diagnostics.forPlan(request.planId()));
    try(var scope=diagnostics.scope(trace)){var run=runs.enqueue(key,request.planId());if(diagnostics.forRun(run.id())==null)diagnostics.run(trace,run.id());return run;}
  }
  @PostMapping("/runs/{id}/recover") public Object recover(@PathVariable String id){try(var scope=diagnostics.scope(diagnostics.forRun(id))){return runs.recover(id);}}
}

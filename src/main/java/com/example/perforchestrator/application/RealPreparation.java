package com.example.perforchestrator.application;

import com.example.perforchestrator.domain.*;
import com.example.perforchestrator.domain.Model.*;
import com.example.perforchestrator.infrastructure.config.*;
import com.example.perforchestrator.infrastructure.execution.*;
import com.example.perforchestrator.infrastructure.registry.ArtifactoryImages;
import com.example.perforchestrator.infrastructure.persistence.Store;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class RealPreparation {
  public record Deployment(String serviceId, String revision, String imageVersion, List<String> valuesFiles,
      String overlay, Map<String,String> valuesEdits) {
    public Deployment(String serviceId, String revision, String imageVersion, List<String> valuesFiles, String overlay) {
      this(serviceId, revision, imageVersion, valuesFiles, overlay, Map.of());
    }
  }
  public record Metric(String serviceId, String name, String query) {}
  public record Request(String name, List<Deployment> services, Deployment loadGenerator, int warmupSeconds,
      int measurementSeconds, int maxRunDurationSeconds, List<Metric> metrics, List<Threshold> thresholds) {}
  private final Catalog catalog; private final SparseProjects projects; private final HelmExecution helm;
  private final ArtifactoryImages images; private final Store store; private final ExecutionSettings settings; private final com.example.perforchestrator.infrastructure.registry.ConnectionConfig connections;
  public RealPreparation(Catalog catalog, SparseProjects projects, HelmExecution helm, ArtifactoryImages images, Store store, ExecutionSettings settings, com.example.perforchestrator.infrastructure.registry.ConnectionConfig connections) {
    this.catalog=catalog;this.projects=projects;this.helm=helm;this.images=images;this.store=store;this.settings=settings; this.connections=connections;
  }
  public void enabled() {
    if (!catalog.mode().equals("real") || !settings.enabled) throw new Problem(409,"REAL_EXECUTION_DISABLED","execution","Enable real execution for the configured cluster first");
  }
  public Object profiles(String id, String revision) {
    enabled(); var svc=catalog.service(id); var snapshot=projects.checkout(svc.sourceProject(),revision);
    Map<String,String> values=new TreeMap<>();
    snapshot.files().forEach((path,encoded) -> {
      if (path.substring(path.lastIndexOf('/')+1).matches("values[^/]*\\.ya?ml") && !path.contains("/templates/")) {
        byte[] bytes=Base64.getDecoder().decode(encoded);
        if (bytes.length<=65536) values.put(path,new String(bytes,StandardCharsets.UTF_8));
      }
    });
    return Map.of("commit", snapshot.commit(), "valuesFiles", values);
  }
  public Plan prepare(Request request) {
    enabled();
    if(request==null || request.name()==null || request.name().isBlank() || request.name().length()>100 || request.loadGenerator()==null)
      throw Problem.invalid("profile","Name and load-generator service are required");
    if(request.services()==null || request.services().size()>30 || request.warmupSeconds()<0 || request.measurementSeconds()<1
        || request.maxRunDurationSeconds()<60 || request.maxRunDurationSeconds()>28800
        || (long)request.warmupSeconds()+request.measurementSeconds()>=request.maxRunDurationSeconds())
      throw Problem.invalid("duration","Use a 1–480 minute run limit with room for preparation, warmup, measurement and cleanup");
    var target=helm.target();
    var selections=new LinkedHashMap<String,Deployment>();
    for(var selection:request.services()) if(selection==null || selections.put(selection.serviceId(),selection)!=null) throw Problem.invalid("services","Duplicate service");
    if(selections.containsKey(request.loadGenerator().serviceId())) throw Problem.invalid("loadGenerator","Load generator must be separate from deployed services");
    var ordered=new ArrayList<Deployment>(); var done=new HashSet<String>();
    for(String id:selections.keySet()) order(id,selections,new HashSet<>(),done,ordered);
    List<PreparedService> prepared=new ArrayList<>(); Map<String,String> charts=new TreeMap<>();
    Set<String> destinations=new HashSet<>();
    for(var selection:ordered) prepared.add(prepareService(selection,target,charts,destinations));
    for(String dependency:catalog.service(request.loadGenerator().serviceId()).dependencies())
      if(!selections.containsKey(dependency)) throw Problem.invalid("services","Select load-generator dependency: "+dependency);
    var load=prepareService(request.loadGenerator(),target,charts,destinations);
    if(!load.baselineDigest().equals("ABSENT")) throw Problem.conflict("Load-generator release already exists; use a dedicated uninstalled release before preparing a run");
    prepared.add(load);
    var metrics=request.metrics()==null?List.<Metric>of():List.copyOf(request.metrics());
    Set<String> names=new HashSet<>();
    if(metrics.size()>20)throw Problem.invalid("metrics","At most 20 queries are supported");
    for(var metric:metrics) {
      if(metric==null || metric.name()==null || !metric.name().matches("[a-z][a-z0-9_]{0,63}") || !names.add(metric.name())
          || metric.query()==null || metric.query().isBlank() || metric.query().length()>8192
          || prepared.stream().noneMatch(s->s.serviceId().equals(metric.serviceId()))) throw Problem.invalid("metrics","Supply unique named LogQL queries for selected services");
    }
    var thresholds=request.thresholds()==null?List.<Threshold>of():List.copyOf(request.thresholds());
    for(var threshold:thresholds)if(threshold==null || !names.contains(threshold.metric()) || !Double.isFinite(threshold.maximum()))throw Problem.invalid("thresholds","Thresholds must reference a configured metric and finite maximum");
    Map<String,Object> meta=new LinkedHashMap<>();
    meta.put("target",target); meta.put("charts",charts); meta.put("loadService",load.serviceId()); meta.put("metrics",metrics);
    meta.put("connectionHash", Json.hash(Json.write(connections.data())));
    var profile=new Profile(request.name(),catalog.boundEnvironment(), prepared.stream().map(s->new Selection(s.serviceId(),Action.DEPLOY,
        new Build(s.image().sourceRef(),null,s.image().version()),"")).toList(),
        new Load("real-yaml",0,0,request.warmupSeconds(),request.measurementSeconds(),""),request.maxRunDurationSeconds(),thresholds,SimulationCase.SUCCESS);
    Instant now=Instant.now();
    var plan=new Plan(UUID.randomUUID().toString(),"",now.toString(),now.plusSeconds(900).toString(),"local-developer",null,0,profile,
        catalog.hash(),catalog.environment(catalog.boundEnvironment()).clusterIdentity(),load.namespace(),load.sourceRevision(),Map.copyOf(meta),List.copyOf(prepared),
        List.of("REAL: this plan executes Helm in the configured cluster.","Only ckp is checked out; chart dependencies must be vendored.",
          "Images are pinned using imageTag/global.imageTag = tag@digest; rendered chart must reference the selected digest.",
          "Load release must be absent; Stop uninstalls only this run's load release. Service deployments remain.",
          "Helm readiness uses --wait for services. Chart hooks and load-generator behavior are defined by the selected chart.",
          "Load rates and tool duration come from values YAML; orchestrator timing independently bounds the run.",
          metrics.isEmpty()?"Metrics not configured: performance verdict will be INCONCLUSIVE.":"Metrics use supplied LogQL; confirm event counts correspond to messages."),false);
    plan=new Plan(plan.id(),PlanningService.checksum(plan),plan.createdAt(),plan.expiresAt(),plan.actor(),null,0,plan.profile(),plan.catalogHash(),plan.clusterIdentity(),plan.loadNamespace(),plan.scenarioRevision(),plan.effectiveLoadConfiguration(),plan.services(),plan.warnings(),false);
    store.plan(plan); return plan;
  }
  private void order(String id,Map<String,Deployment> selected,Set<String> visiting,Set<String> done,List<Deployment> out) {
    if(done.contains(id))return;
    if(!visiting.add(id) || !selected.containsKey(id))throw Problem.invalid("services","Select all dependencies; dependency cycles are not allowed");
    for(String dep:catalog.service(id).dependencies())order(dep,selected,visiting,done,out);
    visiting.remove(id);done.add(id);out.add(selected.get(id));
  }
  private PreparedService prepareService(Deployment selection,Map<String,Object> target,Map<String,String> charts,Set<String> destinations) {
    var service=catalog.service(selection.serviceId());
    var destination=service.deploymentByEnvironment().getOrDefault(catalog.boundEnvironment(),service.deploymentDefaults());
    if(destination==null || !destination.namespace().matches("[a-z0-9][a-z0-9-]{0,62}") || !destination.releaseName().matches("[a-z0-9][a-z0-9-]{0,52}"))
      throw Problem.invalid("deployment","Configure a valid namespace and Helm release");
    if(!destinations.add(destination.namespace()+"/"+destination.releaseName()))throw Problem.invalid("deployment","Two services cannot share a Helm release");
    if(service.sourceProject()==null || service.containerImage()==null)throw Problem.invalid("service","Configure Git and container image mappings");
    String chart=service.sourceProject().chartPath();ConnectionConfigSafe(chart);
    var snapshot=projects.checkout(service.sourceProject(),selection.revision());
    if(!snapshot.files().containsKey(chart+"/Chart.yaml"))throw Problem.invalid("chartPath","Chart.yaml is missing from the sparse CKP snapshot");
    var image=images.resolve(selection.serviceId(),"service:"+selection.serviceId(),null,selection.imageVersion());
    List<String> values=selection.valuesFiles()==null || selection.valuesFiles().isEmpty()?destination.valuesFiles():selection.valuesFiles();
    var edits=selection.valuesEdits()==null?Map.<String,String>of():selection.valuesEdits();
    if(!values.containsAll(edits.keySet()))throw Problem.invalid("valuesEdits","Edits must belong to selected values files");
    Map<String,Object> effective=new TreeMap<>();
    for(String path:values) {
      ConnectionConfigSafe(path); var encoded=snapshot.files().get(path);
      if(encoded==null)throw Problem.invalid("valuesFiles","Selected values file is absent from the checked-out revision");
      String content=edits.containsKey(path)?edits.get(path):new String(Base64.getDecoder().decode(encoded),StandardCharsets.UTF_8);
      if(content==null)throw Problem.invalid("valuesEdits","Edited YAML content is required");
      effective=HelmValues.merge(effective,HelmValues.parse(content));
    }
    var base=new TreeMap<>(effective);
    effective=HelmValues.merge(effective,HelmValues.parse(selection.overlay()));
    // These defaults match the organization's supplied Helm invocation; overlay can set cluster-specific values.
    effective.put("imageName",service.containerImage().imageName());effective.put("imageTag",image.version()+"@"+image.digest());
    effective=HelmValues.merge(effective,Map.of("global",Map.of("imageTag",image.version()+"@"+image.digest(),"environment",catalog.boundEnvironment())));
    Path folder=null;
    try {
      folder=Files.createTempDirectory(settings.workspace,"prepare-");SparseProjects.materialize(folder,snapshot.files());
      Files.writeString(folder.resolve("effective-values.json"),Json.write(effective));
      helm.validate(folder,chart,destination.namespace(),destination.releaseName(),image.digest());
    } catch(java.io.IOException e){throw Problem.invalid("source","Unable to prepare chart files");}
    finally{if(folder!=null)SparseProjects.delete(folder);}
    var hashes=new TreeMap<String,String>();snapshot.files().forEach((path,content)->hashes.put(path,Json.hash(content)));
    charts.put(selection.serviceId(),chart);
    return new PreparedService(selection.serviceId(),Action.DEPLOY,destination.namespace(),destination.releaseName(),image,
        helm.baseline(target,destination.namespace(),destination.releaseName()),snapshot.commit(),hashes,snapshot.files(),hashes,
        ImmutableConfiguration.values(effective),HelmValues.diff(base,effective),List.of());
  }
  private static void ConnectionConfigSafe(String path) {
    com.example.perforchestrator.infrastructure.registry.ConnectionConfig.safePath(path);
    if(!path.startsWith("ckp/"))throw Problem.invalid("path","Chart and values paths must be within ckp/");
  }
}

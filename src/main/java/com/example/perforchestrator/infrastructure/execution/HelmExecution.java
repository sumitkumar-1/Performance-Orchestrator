package com.example.perforchestrator.infrastructure.execution;

import com.example.perforchestrator.domain.*;
import com.example.perforchestrator.domain.Model.*;
import com.example.perforchestrator.infrastructure.config.Json;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class HelmExecution {
  private final CommandRunner commands;
  private final ExecutionSettings settings;
  public HelmExecution(CommandRunner commands, ExecutionSettings settings) { this.commands=commands; this.settings=settings; }
  public Map<String,Object> target() {
    if (!settings.enabled) throw Problem.invalid("execution", "Enable orchestrator.execution.enabled after configuring the cluster");
    if (!settings.expectedServer.startsWith("https://") || settings.context.isBlank())
      throw Problem.invalid("execution", "Configure kube-context and expected-api-server; use in-cluster for a CKP service account");
    try {
      Files.createDirectories(settings.workspace);
      Map<String,Object> target = new LinkedHashMap<>();
      target.put("context", settings.context); target.put("server", settings.expectedServer);
      if (settings.context.equals("in-cluster")) {
        Path account = Path.of("/var/run/secrets/kubernetes.io/serviceaccount");
        if (!Files.isReadable(account.resolve("token"))) throw Problem.invalid("execution", "CKP service account token is not mounted");
        Path file = settings.workspace.resolve("in-cluster-kubeconfig.json");
        Files.writeString(file, Json.write(Map.of("apiVersion", "v1", "kind", "Config", "current-context", "orchestrator",
            "clusters", List.of(Map.of("name", "target", "cluster", Map.of("server", settings.expectedServer, "certificate-authority", account.resolve("ca.crt").toString()))),
            "users", List.of(Map.of("name", "service-account", "user", Map.of("tokenFile", account.resolve("token").toString()))),
            "contexts", List.of(Map.of("name", "orchestrator", "context", Map.of("cluster", "target", "user", "service-account"))))));
        target.put("kubeconfig", file.toString()); target.put("context", "orchestrator");
      }
      verifyTarget(target);
      return Map.copyOf(target);
    } catch (java.io.IOException e) { throw Problem.invalid("execution", "Unable to prepare cluster configuration"); }
  }
  public void verifyTarget(Map<String,Object> target) {
    var args = kubectl(target); args.addAll(List.of("config", "view", "--minify", "-o", "jsonpath={.clusters[0].cluster.server}"));
    String server = call(args, settings.workspace, "Cluster identity check").strip();
    if (!server.equals(target.get("server"))) throw Problem.invalid("execution", "Kube-context API server differs from expected-api-server; refusing deployment");
  }
  private List<String> kubectl(Map<String,Object> target) {
    var args = new ArrayList<>(List.of("kubectl", "--context", target.get("context").toString(), "--request-timeout=30s"));
    if (target.containsKey("kubeconfig")) args.addAll(List.of("--kubeconfig", target.get("kubeconfig").toString()));
    return args;
  }
  private List<String> helm(Map<String,Object> target) {
    var args = new ArrayList<>(List.of("helm", "--kube-context", target.get("context").toString()));
    if (target.containsKey("kubeconfig")) args.addAll(List.of("--kubeconfig", target.get("kubeconfig").toString()));
    return args;
  }
  public String baseline(Map<String,Object> target, String namespace, String release) {
    verifyTarget(target);
    var args=helm(target); args.addAll(List.of("list", "--all", "--namespace", namespace, "--filter", "^"+release+"$", "--output", "json"));
    var result = Json.read(call(args, settings.workspace, "Helm release lookup"), List.class);
    if (result.isEmpty()) return "ABSENT";
    var status=status(target, namespace, release);
    return Json.hash(Json.write(status));
  }
  public Map<String,Object> status(Map<String,Object> target, String namespace, String release) {
    var args=helm(target); args.addAll(List.of("status", release, "--namespace", namespace, "--output", "json"));
    return Json.read(call(args, settings.workspace, "Helm release status"), Map.class);
  }
  public void validate(Path folder, String chart, String namespace, String release, String digest) {
    validationCall(List.of("helm", "lint", folder.resolve(chart).toString(), "--namespace", namespace, "--values", folder.resolve("effective-values.json").toString()), folder, chart, "lint");
    // Rendering is intentionally local; never persist rendered manifests containing Secrets.
    String rendered=validationCall(List.of("helm", "template", release, folder.resolve(chart).toString(), "--namespace", namespace,
        "--values", folder.resolve("effective-values.json").toString()), folder, chart, "render");
    var loaderOptions=new org.yaml.snakeyaml.LoaderOptions();
    loaderOptions.setAllowDuplicateKeys(false);loaderOptions.setMaxAliasesForCollections(30);
    boolean pinned=false;
    try {
      for(Object document:new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(loaderOptions)).loadAll(rendered))
        pinned |= containsPinnedContainer(document,digest);
    } catch(RuntimeException e) { throw Problem.invalid("chart", "Rendered Helm manifests are invalid YAML"); }
    if (!pinned) throw Problem.invalid("image", "Rendered chart does not reference the selected image digest. Chart must consume imageTag/global.imageTag as tag@digest");
  }
  private static boolean containsPinnedContainer(Object node,String digest) {
    boolean found=false;
    if(node instanceof Map<?,?> map) {
      for(var entry:map.entrySet()) {
        if((entry.getKey().equals("containers") || entry.getKey().equals("initContainers")) && entry.getValue() instanceof List<?> containers)
          for(Object item:containers)if(item instanceof Map<?,?> container && container.get("image") instanceof String image && image.endsWith("@"+digest))found=true;
        found |= containsPinnedContainer(entry.getValue(),digest);
      }
    } else if(node instanceof List<?> list) for(Object item:list)found |= containsPinnedContainer(item,digest);
    return found;
  }
  public void apply(String runId, Map<String,Object> target, PreparedService service, String chart, boolean load) {
    verifyTarget(target);
    try {
      Path folder=Files.createTempDirectory(settings.workspace, "apply-");
      try {
        SparseProjects.materialize(folder, service.preparedFiles());
        Files.writeString(folder.resolve("effective-values.json"), Json.write(service.effectiveValues()));
        var args=helm(target);
        // Load must never upgrade or claim an existing release.
        if (load) args.addAll(List.of("install", service.releaseName(), folder.resolve(chart).toString()));
        else args.addAll(List.of("upgrade", "--install", service.releaseName(), folder.resolve(chart).toString()));
        args.addAll(List.of("--namespace", service.namespace(), "--values", folder.resolve("effective-values.json").toString(),
            "--description", "perf-orchestrator:"+runId, "--timeout", settings.timeoutSeconds+"s"));
        if (!load) args.add("--wait");
        // No create-namespace, dependency-update, take-ownership or destructive rollback.
        call(args, folder, load ? "Load-generator installation" : "Service deployment and readiness");
      } finally { SparseProjects.delete(folder); }
    } catch(java.io.IOException e) { throw Problem.invalid("execution", "Unable to materialize the prepared Helm snapshot"); }
  }
  public boolean stop(String runId, Map<String,Object> target, PreparedService load) {
    verifyTarget(target);
    if (baseline(target, load.namespace(), load.releaseName()).equals("ABSENT")) return true;
    var status=status(target, load.namespace(), load.releaseName());
    var info=(Map<?,?>)status.get("info");
    if (info==null || !("perf-orchestrator:"+runId).equals(info.get("description")))
      throw Problem.conflict("Load release ownership differs; refusing to uninstall another release");
    var args=helm(target); args.addAll(List.of("uninstall", load.releaseName(), "--namespace", load.namespace(), "--wait", "--timeout", settings.timeoutSeconds+"s"));
    call(args, settings.workspace, "Owned load-generator cleanup");
    return baseline(target,load.namespace(),load.releaseName()).equals("ABSENT");
  }
  private String validationCall(List<String> args,Path folder,String chart,String operation) {
    CommandRunner.Result result;
    try { result=commands.run(args,folder,environment(),Duration.ofSeconds(settings.timeoutSeconds+15L)); }
    catch(Problem error) { throw new Problem(error.status(),error.code(),error.field(),"Helm "+operation+": "+error.getMessage()); }
    if(result.exit()!=0)throw HelmDiagnostics.failure(operation,result.exit(),result.output(),folder,chart);
    return result.output();
  }
  private Map<String,String> environment() {
    return Map.of("HELM_CACHE_HOME", settings.workspace.resolve("helm-cache").toString(),
        "HELM_CONFIG_HOME", settings.workspace.resolve("helm-config").toString(), "HELM_DATA_HOME", settings.workspace.resolve("helm-data").toString());
  }
  private String call(List<String> args, Path dir, String operation) {
    return commands.require(args,dir,environment(),Duration.ofSeconds(settings.timeoutSeconds+15L),operation);
  }
}

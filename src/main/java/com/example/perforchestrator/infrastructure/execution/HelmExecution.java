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
  private final HelmRepositoryAuth repositoryAuth;
  private volatile int helmMajor;
  public HelmExecution(CommandRunner commands, ExecutionSettings settings) { this(commands,settings,null); }
  @org.springframework.beans.factory.annotation.Autowired
  public HelmExecution(CommandRunner commands, ExecutionSettings settings,HelmRepositoryAuth repositoryAuth) {
    this.commands=commands; this.settings=settings;this.repositoryAuth=repositoryAuth;
  }
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
    var args=helm(target); args.add("list");
    // Helm 3 defaults to deployed/failed; Helm 4 defaults to every status and removed --all.
    if(helmMajor()==3)args.add("--all");
    args.addAll(List.of("--namespace", namespace, "--filter", "^"+release+"$", "--output", "json"));
    var result = Json.read(call(args, settings.workspace, "Helm release lookup"), List.class);
    if (result.isEmpty()) return "ABSENT";
    var releaseRecord=new LinkedHashMap<>(status(target, namespace, release));
    // Helm 4 adds live Kubernetes objects to info.resources on every status lookup.
    // Pod/resource changes are not Helm release changes. Preserve all stored release
    // fields (revision, status, chart, values, manifest, etc.) in the comparison.
    if(releaseRecord.get("info") instanceof Map<?,?> info) {
      var storedInfo=new LinkedHashMap<>(info);
      storedInfo.remove("resources");
      releaseRecord.put("info",storedInfo);
    }
    return Json.hash(Json.write(releaseRecord));
  }
  private synchronized int helmMajor() {
    if(helmMajor!=0)return helmMajor;
    String version=call(List.of("helm","version","--template","{{.Version}}"),settings.workspace,"Helm version check").strip();
    var match=java.util.regex.Pattern.compile("v?([0-9]+)\\.[0-9]+\\.[0-9]+(?:[-+].*)?").matcher(version);
    if(!match.matches() || !Set.of("3","4").contains(match.group(1)))
      throw Problem.invalid("execution","Unable to identify a supported Helm version (3 or 4). Check the Helm executable used by the application");
    helmMajor=Integer.parseInt(match.group(1));return helmMajor;
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
  public void prepareDependencies(Path folder, String chart) throws java.io.IOException {
    Path directory=folder.resolve(chart);
    Path metadata=Files.exists(directory.resolve("requirements.yaml"))
        ?directory.resolve("requirements.yaml"):directory.resolve("Chart.yaml");
    Object configured=HelmValues.parse(Files.readString(metadata)).get("dependencies");
    if(configured==null || configured instanceof List<?> list && list.isEmpty())return;
    if(!(configured instanceof List<?> dependencies))throw Problem.invalid("chart", "Chart dependencies must be a list");
    Set<String> aliases=new TreeSet<>();
    for(Object dependency:dependencies) {
      if(!(dependency instanceof Map<?,?> entry))throw Problem.invalid("chart", "Invalid chart dependency");
      String repository=Objects.toString(entry.get("repository"), "");
      if(repository.startsWith("file://")) {
        Path local=directory.resolve(repository.substring(7)).normalize();
        if(!local.startsWith(folder.resolve("ckp").normalize()))
          throw Problem.invalid("chart", "Local chart dependencies must remain inside the CKP snapshot");
      } else if(repository.startsWith("@") || repository.startsWith("alias:")) {
        String alias=repository.startsWith("@")?repository.substring(1):repository.substring(6);
        if(!alias.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}"))throw Problem.invalid("chart", "Invalid Helm repository alias");
        if(!settings.helmRepositories.containsKey(alias))throw Problem.invalid("execution.helm-repositories",
            "Configure orchestrator.execution.helm-repositories."+alias+" with the chart repository HTTPS URL");
        aliases.add(alias);
      } else if(!repository.isEmpty()) {
        try {
          var uri=java.net.URI.create(repository);
          if(!Set.of("https", "oci").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null)
            throw new IllegalArgumentException();
        } catch(IllegalArgumentException e) {
          throw Problem.invalid("chart", "Chart repositories must use HTTPS, OCI, a configured alias or a CKP-local file reference without embedded credentials");
        }
      }
    }
    // Each preparation has its own alias file/cache; concurrent reviews cannot overwrite each other's repositories.
    var dependencyEnvironment=new HashMap<>(environment());
    Path repositoryHome=folder.resolve(".helm-repositories");Files.createDirectories(repositoryHome);
    try { Files.setPosixFilePermissions(repositoryHome,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")); }
    catch(UnsupportedOperationException e) {
      if(!settings.helmRepositoryConnection.isEmpty())throw Problem.invalid("execution","Authenticated Helm repositories require a filesystem supporting private POSIX directory permissions");
    }
    dependencyEnvironment.put("HELM_REPOSITORY_CONFIG",repositoryHome.resolve("repositories.yaml").toString());
    dependencyEnvironment.put("HELM_REPOSITORY_CACHE",repositoryHome.resolve("cache").toString());
    try {
    for(String alias:aliases) {
      String url=settings.helmRepositories.get(alias);
      var args=new ArrayList<>(List.of("helm","repo","add",alias,url,"--force-update"));
      var credential=repositoryAuth==null?null:repositoryAuth.resolve(settings,url);
      CommandRunner.Result registered;
      if(credential==null)registered=commands.run(args,folder,dependencyEnvironment,Duration.ofSeconds(settings.timeoutSeconds+15L));
      else {
        args.addAll(List.of("--username",credential.username,"--password-stdin"));
        byte[] input=(credential.token+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try { registered=commands.runWithInput(args,folder,dependencyEnvironment,Duration.ofSeconds(settings.timeoutSeconds+15L),input); }
        finally { Arrays.fill(input,(byte)0); }
      }
      if(registered.exit()!=0)throw new Problem(422,"HELM_REPOSITORY_FAILED","execution",
          "Helm repository registration failed for "+alias+" (exit "+registered.exit()+"). Check the configured URL, network/CA trust and repository access. "
          +"For authenticated repositories, check helm-repository-connection and helm-repository-username, and the token's Helm repository read permission. Raw output is withheld.");
    }
    String operation=Files.exists(directory.resolve("Chart.lock")) || Files.exists(directory.resolve("requirements.lock"))?"build":"update";
    var result=commands.run(List.of("helm", "dependency", operation, directory.toString()),folder,
        dependencyEnvironment,Duration.ofSeconds(settings.timeoutSeconds+15L));
    if(result.exit()!=0)throw new Problem(422,"HELM_DEPENDENCY_FAILED","execution",
        "Helm dependency "+operation+" failed (exit "+result.exit()+"). Check the chart's dependency repository URL, access credentials, network/CA trust and lock-file consistency. "
        +"Repository aliases come from orchestrator.execution.helm-repositories. Raw output is withheld because repository errors may contain credentials.");
    } finally { SparseProjects.delete(repositoryHome); }
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

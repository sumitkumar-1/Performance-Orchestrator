package com.example.perforchestrator.infrastructure.config;

import com.example.perforchestrator.domain.Problem;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Catalog {
  public record Limits(int maxRunDurationSeconds, int maxVirtualUsers, int maxRequestsPerSecond) {}

  @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  public record Environment(
      String displayName,
      String clusterIdentity,
      List<String> serviceNamespaces,
      String loadGeneratorNamespace,
      List<String> allowedActions,
      Limits limits,
      String dashboardUrl,
      Monitoring monitoring) {
    public Environment {
      serviceNamespaces = ImmutableConfiguration.list(serviceNamespaces);
      allowedActions = ImmutableConfiguration.list(allowedActions);
    }
  }

  public record NamespaceCredentials(String logsCredentialRef, String metricsCredentialRef) {}
  @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  public record Monitoring(String logsApiBaseUrl, String metricsApiBaseUrl,
      Map<String, NamespaceCredentials> namespaceCredentials, String connectionRef) {
    public Monitoring(String logsApiBaseUrl, String metricsApiBaseUrl, Map<String, NamespaceCredentials> namespaceCredentials) {
      this(logsApiBaseUrl, metricsApiBaseUrl, namespaceCredentials, null);
    }
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public Monitoring { namespaceCredentials = ImmutableConfiguration.map(namespaceCredentials); }
  }

  public record Destination(String namespace, String releaseName, List<String> valuesFiles) {
    public Destination {
      valuesFiles = ImmutableConfiguration.list(valuesFiles);
    }
  }

  public record Binding(
      String file,
      String exactLine,
      int expectedOccurrences,
      String helmValuesKey,
      String selectorKey,
      Map<String, String> sourceSelectors) {
    public Binding {
      sourceSelectors = ImmutableConfiguration.map(sourceSelectors);
    }
  }

  public record ContainerImage(String connectionRef, String repoStage, String teamId, String imageName) {}
  public record SourceProject(String connectionRef, String projectKey, String repository, String revision, String chartPath, String cloneUrl) {
    public SourceProject(String connectionRef, String projectKey, String repository, String revision, String chartPath) {
      this(connectionRef, projectKey, repository, revision, chartPath, null);
    }
    @org.springframework.boot.context.properties.bind.ConstructorBinding public SourceProject {}
  }

  public record Service(
      String projectPath,
      List<String> dependencies,
      Map<String, Destination> deploymentByEnvironment,
      Binding installationBindings,
      List<String> allowedOverridePaths,
      Destination deploymentDefaults,
      ContainerImage containerImage,
      SourceProject sourceProject,
      Map<String, String> monitoringCredentials) {
    public Service(String projectPath, List<String> dependencies, Map<String, Destination> deploymentByEnvironment,
        Binding installationBindings, List<String> allowedOverridePaths, Destination deploymentDefaults,
        ContainerImage containerImage, SourceProject sourceProject) {
      this(projectPath, dependencies, deploymentByEnvironment, installationBindings, allowedOverridePaths, deploymentDefaults, containerImage, sourceProject, Map.of());
    }
    public Service(String projectPath, List<String> dependencies, Map<String, Destination> deploymentByEnvironment,
        Binding installationBindings, List<String> allowedOverridePaths, Destination deploymentDefaults) {
      this(projectPath, dependencies, deploymentByEnvironment, installationBindings, allowedOverridePaths, deploymentDefaults, null, null);
    }
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public Service {
      monitoringCredentials = ImmutableConfiguration.map(monitoringCredentials == null ? Map.of() : monitoringCredentials);
      dependencies = ImmutableConfiguration.list(dependencies);
      deploymentByEnvironment = ImmutableConfiguration.map(deploymentByEnvironment == null ? Map.of() : deploymentByEnvironment);
      allowedOverridePaths = ImmutableConfiguration.list(allowedOverridePaths);
    }
  }

  public record Source(
      String displayName,
      boolean usernameRequired,
      String repositoryTemplate,
      List<String> versions) {
    public Source {
      versions = ImmutableConfiguration.list(versions);
    }
  }

  public record Scenario(
      String displayName,
      String revision,
      List<String> allowedOverridePaths,
      Map<String, Object> defaults) {
    public Scenario {
      allowedOverridePaths = ImmutableConfiguration.list(allowedOverridePaths);
      defaults = ImmutableConfiguration.values(defaults);
    }
  }

  public record Data(
      String mode,
      Map<String, Environment> environments,
      Map<String, Service> services,
      Map<String, Source> imageSources,
      Map<String, Scenario> scenarios) {
    public Data {
      if ("real".equals(mode) && environments != null) {
        Map<String, Environment> realEnvironments = new LinkedHashMap<>();
        environments.forEach((id, env) -> realEnvironments.put(id, env == null ? null
            : new Environment(env.displayName(), env.clusterIdentity(), null, null, null, null, null, env.monitoring())));
        environments = realEnvironments;
      }
      environments = ImmutableConfiguration.map(environments);
      services = ImmutableConfiguration.map(services);
      imageSources = ImmutableConfiguration.map(imageSources);
      scenarios = ImmutableConfiguration.map(scenarios);
    }
  }

  private record Snapshot(Data data, String hash) {}

  private volatile Snapshot snapshot;
  private final Path root;
  private final String resourceRoot;
  private final String mode;
  private String boundEnvironment = "";
  private String boundCluster = "";

  public String boundEnvironment() { return boundEnvironment; }

  @org.springframework.beans.factory.annotation.Autowired
  public Catalog(
      @Value("${orchestrator.catalog:}") String file,
      @Value("${orchestrator.mode}") String mode,
      @Value("${server.address:127.0.0.1}") String address,
      org.springframework.core.env.Environment environment) throws IOException {
    this(file, mode, address);
    if (file.isBlank()) {
      var defaults = org.springframework.boot.context.properties.bind.Binder.get(environment)
          .bind("orchestrator.catalog-defaults", Data.class)
          .orElseGet(() -> new Data(mode, Map.of(), Map.of(), Map.of(), Map.of()));
      replace(new Data(mode,
          defaults.environments() == null ? Map.of() : defaults.environments(),
          defaults.services() == null ? Map.of() : defaults.services(),
          defaults.imageSources() == null ? Map.of() : defaults.imageSources(),
          defaults.scenarios() == null ? Map.of() : defaults.scenarios()));
    }
    if (mode.equals("real")) {
      String target = environment.getProperty("orchestrator.target-environment", "");
      if (target.isBlank()) throw new IllegalArgumentException("Real mode requires target-environment");
      if (!target.isBlank()) {
        var selected = data().environments().get(target);
        if (selected == null) throw new IllegalArgumentException("Unknown target environment: " + target);
        boundEnvironment = target;
        boundCluster = selected.clusterIdentity();
        Map<String, Service> services = new LinkedHashMap<>();
        data().services().forEach((id, service) -> {
          var destination = service.deploymentByEnvironment().getOrDefault(target, service.deploymentDefaults());
          if (destination != null) services.put(id, new Service(service.projectPath(), service.dependencies(),
              (service.deploymentByEnvironment().containsKey(target) ? Map.of(target, destination) : Map.of()), service.installationBindings(), service.allowedOverridePaths(), service.deploymentDefaults(), service.containerImage(), service.sourceProject(), service.monitoringCredentials()));
        });
        replace(new Data(mode, Map.of(target, selected), services, data().imageSources(), data().scenarios()));
      }
    }
  }

  public Catalog(
      @Value("${orchestrator.catalog}") String file,
      @Value("${orchestrator.mode}") String mode,
      @Value("${server.address:127.0.0.1}") String address)
      throws IOException {
    if (!Set.of("simulation", "real").contains(mode))
      throw new IllegalStateException("Mode must be simulation or real");
    if (!Set.of("127.0.0.1", "::1", "localhost").contains(address))
      throw new IllegalStateException(
          "Only loopback access is enabled; shared access requires authentication");
    this.mode = mode;
    if (file.isBlank() && mode.equals("real")) {
      root = Path.of(".").toAbsolutePath().normalize();
      resourceRoot = null;
      replace(new Data(mode, Map.of(), Map.of(), Map.of(), Map.of()));
      return;
    }
    if (file.startsWith("classpath:")) {
      resourceRoot = file.substring(0, file.lastIndexOf('/') + 1);
      root = null;
    } else {
      root = Path.of(file).toRealPath().getParent();
      resourceRoot = null;
    }
    replace(parse(ConfigurationResources.read(file)));
  }

  public static Data parse(String content) {
    return Json.read(Json.write(YamlValues.parse(content)), Data.class);
  }

  public void validate(Data candidate) {
    if (candidate == null || candidate.environments() == null || candidate.services() == null
        || candidate.imageSources() == null || candidate.scenarios() == null)
      throw Problem.invalid("catalog", "Invalid catalog: environments, services, imageSources and scenarios maps are required");
    if (!mode.equals(candidate.mode()))
      throw Problem.invalid("catalog.mode", "Invalid catalog: saved/imported mode must match the startup mode");
    if (!boundEnvironment.isBlank()) {
      if (!candidate.environments().keySet().equals(Set.of(boundEnvironment)))
        throw Problem.invalid("catalog.environments", "Invalid catalog: this instance requires exactly its startup target environment (orchestrator.target-environment); saved/imported configuration belongs to another environment or an older layout");
      if (candidate.environments().get(boundEnvironment) == null
          || !boundCluster.equals(candidate.environments().get(boundEnvironment).clusterIdentity()))
        throw Problem.invalid("catalog.environments.clusterIdentity", "Invalid catalog: saved/imported cluster identity differs from the fixed startup cluster; review startup defaults and the saved configuration");
    }
    final String[] field = {"catalog"};
    try {
      if (mode.equals("simulation")
          && (candidate.environments().isEmpty()
              || candidate.services().isEmpty()
              || candidate.imageSources().isEmpty()
              || candidate.scenarios().isEmpty()))
        throw new IllegalArgumentException("Catalog sections cannot be empty");
      candidate
          .environments()
          .forEach(
              (id, env) -> {
                field[0] = "catalog.environments";
                identifier(id);
                field[0] += "." + id;

                required(env.displayName());
                required(env.clusterIdentity());
                if (mode.equals("simulation")) required(env.loadGeneratorNamespace());
                if (mode.equals("simulation") && (env.serviceNamespaces().isEmpty()
                    || env.allowedActions() == null
                    || !Set.of("PLAN", "DEPLOY", "RUN_LOAD").containsAll(env.allowedActions())
                    || env.limits().maxRunDurationSeconds() < 15
                    || env.limits().maxVirtualUsers() < 1
                    || env.limits().maxRequestsPerSecond() < 1))
                  throw new IllegalArgumentException();
                if (env.monitoring() != null) {
                  field[0] = "catalog.environments." + id + ".monitoring";
                  var monitoring = env.monitoring();
                  for (String url : List.of(
                      Objects.toString(monitoring.logsApiBaseUrl(), ""),
                      Objects.toString(monitoring.metricsApiBaseUrl(), "")))
                    if (!url.isBlank()) com.example.perforchestrator.infrastructure.registry.ConnectionConfig.base(url);
                  if (monitoring.connectionRef() != null) identifier(monitoring.connectionRef());
                }
                if (env.dashboardUrl() != null && !env.dashboardUrl().isBlank())
                  com.example.perforchestrator.infrastructure.registry.ConnectionConfig.base(
                      env.dashboardUrl());
              });
      candidate
          .imageSources()
          .forEach(
              (id, source) -> {
                field[0] = "catalog.imageSources";
                identifier(id);
                field[0] += "." + id;
                required(source.displayName());
                required(source.repositoryTemplate());
                if (source.versions().isEmpty()
                    || source.versions().stream()
                        .anyMatch(
                            v -> v == null || !v.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")))
                  throw new IllegalArgumentException();
              });
      candidate
          .services()
          .forEach(
              (id, service) -> {
                field[0] = "catalog.services";
                identifier(id);
                field[0] += "." + id;
                safeRelative(service.projectPath());
                if (!candidate.services().keySet().containsAll(service.dependencies())
                    || service.dependencies().contains(id)
                    || (mode.equals("simulation") && service.allowedOverridePaths() == null)
                    || (service.deploymentByEnvironment().isEmpty() && service.deploymentDefaults() == null))
                  throw new IllegalArgumentException();
                if (mode.equals("real")) {
                  // Real service entries describe intended deployment inputs. Source retrieval
                  // and executable chart validation remain blocked by requireExecution().
                  Map<String, Destination> destinations = new LinkedHashMap<>(service.deploymentByEnvironment());
                  if (service.deploymentDefaults() != null) candidate.environments().keySet()
                      .forEach(env -> destinations.putIfAbsent(env, service.deploymentDefaults()));
                  destinations.forEach((environment, destination) -> {
                    field[0] = "catalog.services." + id + ".deploymentDefaults/deploymentByEnvironment";
                    var env = candidate.environments().get(environment);
                    if (env == null || destination.namespace() == null || destination.namespace().isBlank()
                        || destination.valuesFiles() == null || destination.valuesFiles().isEmpty())
                      throw new IllegalArgumentException();
                    required(destination.releaseName());
                    destination.valuesFiles().forEach(Catalog::safeRelative);
                  });
                  return;
                }
                var binding = service.installationBindings();
                required(binding.exactLine());
                required(binding.helmValuesKey());
                required(binding.selectorKey());
                if (binding.expectedOccurrences() < 1
                    || binding.sourceSelectors().isEmpty()
                    || !candidate
                        .imageSources()
                        .keySet()
                        .containsAll(binding.sourceSelectors().keySet()))
                  throw new IllegalArgumentException();
                read(service, binding.file());
                service
                    .deploymentByEnvironment()
                    .forEach(
                        (environment, destination) -> {
                          var env = candidate.environments().get(environment);
                          if (env == null
                              || !env.serviceNamespaces().contains(destination.namespace())
                              || destination.valuesFiles().isEmpty())
                            throw new IllegalArgumentException();
                          required(destination.releaseName());
                          destination.valuesFiles().forEach(file -> read(service, file));
                        });
              });
      field[0] = "catalog.services.dependencies";
      for (String id : candidate.services().keySet())
        cycle(candidate, id, new HashSet<>(), new HashSet<>());
      candidate
          .scenarios()
          .forEach(
              (id, scenario) -> {
                field[0] = "catalog.scenarios";
                identifier(id);
                field[0] += "." + id;
                required(scenario.displayName());
                required(scenario.revision());
                if (scenario.allowedOverridePaths() == null || scenario.defaults() == null)
                  throw new IllegalArgumentException();
              });
    } catch (RuntimeException error) {
      throw Problem.invalid(
          field[0],
          "Invalid catalog at " + field[0]
              + ": check required fields, paths, references and (for simulation) limits/project files");
    }
  }

  private static void cycle(Data data, String id, Set<String> visiting, Set<String> done) {
    if (done.contains(id)) return;
    if (!visiting.add(id)) throw new IllegalArgumentException();
    for (String dependency : data.services().get(id).dependencies())
      cycle(data, dependency, visiting, done);
    visiting.remove(id);
    done.add(id);
  }

  private static void identifier(String id) {
    if (id == null || !id.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException();
  }

  private static void required(String text) {
    if (text == null || text.isBlank()) throw new IllegalArgumentException();
  }

  private static void safeRelative(String path) {
    if (path == null
        || !path.matches("[A-Za-z0-9_./-]+")
        || path.startsWith("/")
        || Arrays.stream(path.split("/", -1))
            .anyMatch(p -> p.isEmpty() || p.equals("..") || p.equals(".")))
      throw Problem.invalid(
          "projectPath", "Project paths must be relative and cannot traverse directories");
  }

  public void replace(Data next) {
    validate(next);
    installValidated(next);
  }

  public void installValidated(Data next) {
    snapshot = new Snapshot(next, Json.hash(Json.write(next)));
  }

  public String mode() {
    return mode;
  }

  public void requireExecution() {
    if (mode.equals("real"))
      throw new Problem(
          501,
          "REAL_EXECUTION_UNAVAILABLE",
          "mode",
          "Real deployment and load execution adapters are not configured. Real mode supports"
              + " Artifactory discovery and Delinea authentication; no simulation fallback is"
              + " used.");
  }

  public Data data() {
    return snapshot.data();
  }

  public String hash() {
    return snapshot.hash();
  }

  public Environment environment(String id) {
    var value = data().environments().get(id);
    if (value == null) throw Problem.invalid("targetEnvironment", "Unknown environment");
    return value;
  }

  public Service service(String id) {
    var value = data().services().get(id);
    if (value == null) throw Problem.invalid("services", "Unknown service");
    return value;
  }

  public Path project(Service service) {
    try {
      safeRelative(service.projectPath());
      if (root == null)
        throw Problem.invalid("projectPath", "Packaged mock projects are read as resources");
      Path path = root.resolve(service.projectPath()).toRealPath();
      if (!path.startsWith(root))
        throw Problem.invalid("projectPath", "Project must stay within catalog fixture root");
      return path;
    } catch (IOException e) {
      throw Problem.invalid("projectPath", "Registered project is missing");
    }
  }

  public String read(Service service, String relative) {
    try {
      safeRelative(service.projectPath());
      safeRelative(relative);
      if (resourceRoot != null) {
        String content =
            ConfigurationResources.read(resourceRoot + service.projectPath() + "/" + relative);
        if (content.length() > 65536) throw Problem.invalid("chart.path", "Oversized project file");
        return content;
      }
      Path base = project(service);
      Path path = base.resolve(relative).toRealPath();
      if (!path.startsWith(base) || !Files.isRegularFile(path) || Files.size(path) > 65536)
        throw Problem.invalid("chart.path", "Invalid or oversized project file");
      return Files.readString(path);
    } catch (IOException e) {
      throw Problem.invalid("chart.path", "Registered chart/values file is missing");
    }
  }
}

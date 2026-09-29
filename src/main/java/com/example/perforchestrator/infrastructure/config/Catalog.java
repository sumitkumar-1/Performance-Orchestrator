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

  public record Environment(
      String displayName,
      String clusterIdentity,
      List<String> serviceNamespaces,
      String loadGeneratorNamespace,
      List<String> allowedActions,
      Limits limits,
      String dashboardUrl) {
    public Environment {
      serviceNamespaces = ImmutableConfiguration.list(serviceNamespaces);
      allowedActions = ImmutableConfiguration.list(allowedActions);
    }
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

  public record Service(
      String projectPath,
      List<String> dependencies,
      Map<String, Destination> deploymentByEnvironment,
      Binding installationBindings,
      List<String> allowedOverridePaths) {
    public Service {
      dependencies = ImmutableConfiguration.list(dependencies);
      deploymentByEnvironment = ImmutableConfiguration.map(deploymentByEnvironment);
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
    try {
      if (!mode.equals(candidate.mode()))
        throw new IllegalArgumentException("Catalog must match the startup mode");
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
                identifier(id);
                required(env.displayName());
                required(env.clusterIdentity());
                required(env.loadGeneratorNamespace());
                if (env.serviceNamespaces().isEmpty()
                    || env.allowedActions() == null
                    || !Set.of("PLAN", "DEPLOY", "RUN_LOAD").containsAll(env.allowedActions())
                    || env.limits().maxRunDurationSeconds() < 15
                    || env.limits().maxVirtualUsers() < 1
                    || env.limits().maxRequestsPerSecond() < 1)
                  throw new IllegalArgumentException();
                if (env.dashboardUrl() != null && !env.dashboardUrl().isBlank())
                  com.example.perforchestrator.infrastructure.registry.ConnectionConfig.base(
                      env.dashboardUrl());
              });
      candidate
          .imageSources()
          .forEach(
              (id, source) -> {
                identifier(id);
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
                identifier(id);
                safeRelative(service.projectPath());
                if (!candidate.services().keySet().containsAll(service.dependencies())
                    || service.dependencies().contains(id)
                    || service.allowedOverridePaths() == null
                    || service.deploymentByEnvironment().isEmpty())
                  throw new IllegalArgumentException();
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
      for (String id : candidate.services().keySet())
        cycle(candidate, id, new HashSet<>(), new HashSet<>());
      candidate
          .scenarios()
          .forEach(
              (id, scenario) -> {
                identifier(id);
                required(scenario.displayName());
                required(scenario.revision());
                if (scenario.allowedOverridePaths() == null || scenario.defaults() == null)
                  throw new IllegalArgumentException();
              });
    } catch (RuntimeException error) {
      throw Problem.invalid(
          "catalog",
          "Invalid catalog: check required fields, limits, references, dependencies and project"
              + " files");
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

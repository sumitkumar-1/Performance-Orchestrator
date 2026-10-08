package com.example.perforchestrator.infrastructure.config;

import com.example.perforchestrator.domain.Problem;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Catalog {

  @com.fasterxml.jackson.annotation.JsonIgnoreProperties({
    "serviceNamespaces",
    "loadGeneratorNamespace",
    "allowedActions",
    "limits",
    "dashboardUrl",
  })
  public record Environment(String displayName, String clusterIdentity, Monitoring monitoring) {}

  public record NamespaceCredentials(String logsCredentialRef, String metricsCredentialRef) {}

  @com.fasterxml.jackson.annotation.JsonInclude(
    com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL
  )
  public record Monitoring(
    String logsApiBaseUrl,
    String metricsApiBaseUrl,
    Map<String, NamespaceCredentials> namespaceCredentials,
    String connectionRef
  ) {
    public Monitoring(
      final String logsApiBaseUrl,
      final String metricsApiBaseUrl,
      final Map<String, NamespaceCredentials> namespaceCredentials
    ) {
      this(logsApiBaseUrl, metricsApiBaseUrl, namespaceCredentials, null);
    }

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public Monitoring {
      namespaceCredentials = ImmutableConfiguration.map(namespaceCredentials);
    }
  }

  public record Destination(String namespace, String releaseName, List<String> valuesFiles) {
    public Destination {
      valuesFiles = ImmutableConfiguration.list(valuesFiles);
    }
  }

  public record ContainerImage(
    String connectionRef,
    String repoStage,
    String teamId,
    String imageName
  ) {}

  public record SourceProject(
    String connectionRef,
    String projectKey,
    String repository,
    String revision,
    String chartPath,
    String cloneUrl
  ) {
    public SourceProject(
      final String connectionRef,
      final String projectKey,
      final String repository,
      final String revision,
      final String chartPath
    ) {
      this(connectionRef, projectKey, repository, revision, chartPath, null);
    }

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public SourceProject {
    }
  }

  @com.fasterxml.jackson.annotation.JsonIgnoreProperties({
    "dependencies",
    "installationBindings",
    "allowedOverridePaths",
  })
  public record Service(
    String projectPath,
    Map<String, Destination> deploymentByEnvironment,
    Destination deploymentDefaults,
    ContainerImage containerImage,
    SourceProject sourceProject,
    Map<String, String> monitoringCredentials
  ) {
    public Service(
      final String projectPath,
      final Map<String, Destination> deploymentByEnvironment,
      final Destination deploymentDefaults
    ) {
      this(projectPath, deploymentByEnvironment, deploymentDefaults, null, null, Map.of());
    }

    public Service(
      final String projectPath,
      final Map<String, Destination> deploymentByEnvironment,
      final Destination deploymentDefaults,
      final ContainerImage containerImage,
      final SourceProject sourceProject
    ) {
      this(
        projectPath,
        deploymentByEnvironment,
        deploymentDefaults,
        containerImage,
        sourceProject,
        Map.of()
      );
    }

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public Service {
      deploymentByEnvironment = ImmutableConfiguration.map(
        deploymentByEnvironment == null ? Map.of() : deploymentByEnvironment
      );
      monitoringCredentials = ImmutableConfiguration.map(
        monitoringCredentials == null ? Map.of() : monitoringCredentials
      );
    }
  }

  public record Scenario(
    String displayName,
    String revision,
    List<String> allowedOverridePaths,
    Map<String, Object> defaults
  ) {
    public Scenario {
      allowedOverridePaths = ImmutableConfiguration.list(
        allowedOverridePaths == null ? List.of() : allowedOverridePaths
      );
      defaults = ImmutableConfiguration.values(defaults);
    }
  }

  @com.fasterxml.jackson.annotation.JsonIgnoreProperties({ "mode", "imageSources" })
  public record Data(
    Map<String, Environment> environments,
    Map<String, Service> services,
    Map<String, Scenario> scenarios
  ) {
    public Data {
      environments = ImmutableConfiguration.map(environments);
      services = ImmutableConfiguration.map(services);
      scenarios = ImmutableConfiguration.map(scenarios);
    }
  }

  private record Snapshot(Data data, String hash) {}

  private volatile Snapshot snapshot;
  private String boundEnvironment = "";

  public String selectedEnvironment(final String requested) {
    final String selected = requested == null || requested.isBlank() ? boundEnvironment : requested;
    environment(selected);
    return selected;
  }

  public String boundEnvironment() {
    return boundEnvironment;
  }

  @org.springframework.beans.factory.annotation.Autowired
  public Catalog(
    final @Value("${orchestrator.catalog:}") String file,
    final @Value("${server.address:127.0.0.1}") String address,
    final org.springframework.core.env.Environment environment
  ) throws IOException {
    this(file, address);
    if (file.isBlank()) {
      final var defaults = org.springframework.boot.context.properties.bind.Binder.get(environment)
        .bind("orchestrator.catalog-defaults", Data.class)
        .orElseGet(() -> new Data(Map.of(), Map.of(), Map.of()));
      replace(
        new Data(
          defaults.environments() == null ? Map.of() : defaults.environments(),
          defaults.services() == null ? Map.of() : defaults.services(),
          defaults.scenarios() == null ? Map.of() : defaults.scenarios()
        )
      );
    }
    final String target = environment.getProperty("orchestrator.target-environment", "sandbox");
    if (!data().environments().containsKey(target)) throw new IllegalArgumentException(
      "Unknown target environment: " + target
    );
    boundEnvironment = target;
  }

  public Catalog(final String file, final String address) throws IOException {
    if (!Set.of("127.0.0.1", "::1", "localhost").contains(address)) throw new IllegalStateException(
      "Only loopback access is enabled; shared access requires authentication"
    );
    replace(
      file.isBlank()
        ? new Data(Map.of(), Map.of(), Map.of())
        : parse(ConfigurationResources.read(file))
    );
  }

  public static Data parse(final String content) {
    return Json.read(Json.write(YamlValues.parse(content)), Data.class);
  }

  public void validate(final Data candidate) {
    if (
      candidate == null ||
      candidate.environments() == null ||
      candidate.services() == null ||
      candidate.scenarios() == null
    ) throw Problem.invalid(
      "catalog",
      "Invalid catalog: environments, services and scenarios maps are required"
    );
    if (
      !boundEnvironment.isBlank() && !candidate.environments().containsKey(boundEnvironment)
    ) throw Problem.invalid(
      "catalog.environments",
      "Keep the configured default environment in the catalog"
    );
    final String[] field = { "catalog" };
    try {
      candidate.environments().forEach((final var id, final var env) -> {
        field[0] = "catalog.environments";
        identifier(id);
        field[0] += "." + id;

        required(env.displayName());
        required(env.clusterIdentity());
        if (env.monitoring() != null) {
          field[0] = "catalog.environments." + id + ".monitoring";
          final var monitoring = env.monitoring();
          for (final String url : List.of(
            Objects.toString(monitoring.logsApiBaseUrl(), ""),
            Objects.toString(monitoring.metricsApiBaseUrl(), "")
          ))
            if (
              !url.isBlank()
            ) com.example.perforchestrator.infrastructure.registry.ConnectionConfig.base(url);
          if (monitoring.connectionRef() != null) identifier(monitoring.connectionRef());
        }
      });
      candidate.services().forEach((final var id, final var service) -> {
        field[0] = "catalog.services";
        identifier(id);
        field[0] += "." + id;
        safeRelative(service.projectPath());
        if (
          service.deploymentByEnvironment().isEmpty() && service.deploymentDefaults() == null
        ) throw new IllegalArgumentException();
        {
          // Validate deployment mappings; chart validation happens during run review.
          final Map<String, Destination> destinations = new LinkedHashMap<>(
            service.deploymentByEnvironment()
          );
          if (service.deploymentDefaults() != null) candidate
            .environments()
            .keySet()
            .forEach((final var env) ->
              destinations.putIfAbsent(env, service.deploymentDefaults())
            );
          destinations.forEach((final var environment, final var destination) -> {
            field[0] = "catalog.services." + id + ".deploymentDefaults/deploymentByEnvironment";
            final var env = candidate.environments().get(environment);
            if (
              env == null ||
              destination.namespace() == null ||
              destination.namespace().isBlank() ||
              destination.valuesFiles() == null ||
              destination.valuesFiles().isEmpty()
            ) throw new IllegalArgumentException();
            required(destination.releaseName());
            destination.valuesFiles().forEach(Catalog::safeRelative);
          });
        }
      });
      candidate.scenarios().forEach((final var id, final var scenario) -> {
        field[0] = "catalog.scenarios";
        identifier(id);
        field[0] += "." + id;
        required(scenario.displayName());
        required(scenario.revision());
        if (
          scenario.allowedOverridePaths() == null || scenario.defaults() == null
        ) throw new IllegalArgumentException();
      });
    } catch (final RuntimeException error) {
      throw Problem.invalid(
        field[0],
        "Invalid catalog at " + field[0] + ": check required fields, paths and references"
      );
    }
  }

  private static void identifier(final String id) {
    if (id == null || !id.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException();
  }

  private static void required(final String text) {
    if (text == null || text.isBlank()) throw new IllegalArgumentException();
  }

  private static void safeRelative(final String path) {
    if (
      path == null ||
      !path.matches("[A-Za-z0-9_./-]+") ||
      path.startsWith("/") ||
      Arrays.stream(path.split("/", -1)).anyMatch(
        (final var p) -> p.isEmpty() || p.equals("..") || p.equals(".")
      )
    ) throw Problem.invalid(
      "projectPath",
      "Project paths must be relative and cannot traverse directories"
    );
  }

  public void replace(final Data next) {
    validate(next);
    installValidated(next);
  }

  public void installValidated(final Data next) {
    snapshot = new Snapshot(next, Json.hash(Json.write(next)));
  }

  public Data data() {
    return snapshot.data();
  }

  public String hash() {
    return snapshot.hash();
  }

  public Environment environment(final String id) {
    final var value = data().environments().get(id);
    if (value == null) throw Problem.invalid("targetEnvironment", "Unknown environment");
    return value;
  }

  public Service service(final String id) {
    final var value = data().services().get(id);
    if (value == null) throw Problem.invalid("services", "Unknown service");
    return value;
  }
}

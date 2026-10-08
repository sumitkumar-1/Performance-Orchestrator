package com.example.perforchestrator.application;

import com.example.perforchestrator.domain.*;
import com.example.perforchestrator.domain.Model.*;
import com.example.perforchestrator.infrastructure.config.*;
import com.example.perforchestrator.infrastructure.execution.*;
import com.example.perforchestrator.infrastructure.persistence.Store;
import com.example.perforchestrator.infrastructure.registry.ArtifactoryImages;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class RunPreparation {

  public record Deployment(
    String serviceId,
    String revision,
    String imageVersion,
    List<String> valuesFiles,
    String overlay,
    Map<String, String> valuesEdits,
    String gitReference
  ) {
    public Deployment(
      final String serviceId,
      final String revision,
      final String imageVersion,
      final List<String> valuesFiles,
      final String overlay,
      final Map<String, String> valuesEdits
    ) {
      this(serviceId, revision, imageVersion, valuesFiles, overlay, valuesEdits, null);
    }

    public Deployment(
      final String serviceId,
      final String revision,
      final String imageVersion,
      final List<String> valuesFiles,
      final String overlay
    ) {
      this(serviceId, revision, imageVersion, valuesFiles, overlay, Map.of());
    }
  }

  public record Metric(
    String serviceId,
    String name,
    String query,
    String title,
    String namespace,
    String kind,
    String credentialRef
  ) {
    public Metric(final String serviceId, final String name, final String query) {
      this(serviceId, name, query, null, null, null, null);
    }

    public boolean logs() {
      return "logs".equals(kind);
    }
  }

  public record Request(
    String name,
    List<Deployment> services,
    Deployment loadGenerator,
    int warmupSeconds,
    int measurementSeconds,
    int maxRunDurationSeconds,
    List<Metric> metrics,
    List<Threshold> thresholds,
    String targetEnvironment
  ) {
    public Request(
      final String name,
      final List<Deployment> services,
      final Deployment loadGenerator,
      final int warmupSeconds,
      final int measurementSeconds,
      final int maxRunDurationSeconds,
      final List<Metric> metrics,
      final List<Threshold> thresholds
    ) {
      this(
        name,
        services,
        loadGenerator,
        warmupSeconds,
        measurementSeconds,
        maxRunDurationSeconds,
        metrics,
        thresholds,
        null
      );
    }
  }

  private final java.util.concurrent.Semaphore preparationSlots;
  private final Catalog catalog;
  private final SparseProjects projects;
  private final HelmExecution helm;
  private final ArtifactoryImages images;
  private final Store store;
  private final ExecutionSettings settings;
  private final com.example.perforchestrator.infrastructure.registry.ConnectionConfig connections;

  public RunPreparation(
    final Catalog catalog,
    final SparseProjects projects,
    final HelmExecution helm,
    final ArtifactoryImages images,
    final Store store,
    final ExecutionSettings settings,
    final com.example.perforchestrator.infrastructure.registry.ConnectionConfig connections
  ) {
    this.catalog = catalog;
    this.projects = projects;
    this.helm = helm;
    this.images = images;
    this.store = store;
    this.settings = settings;
    this.connections = connections;
    this.preparationSlots = new java.util.concurrent.Semaphore(
      settings.preparationConcurrency,
      true
    );
  }

  public void enabled() {
    if (!settings.enabled) throw new Problem(
      409,
      "EXECUTION_DISABLED",
      "execution",
      "Enable execution for the configured cluster first"
    );
  }

  public Object profiles(final String id, final String revision) {
    enabled();
    final var svc = catalog.service(id);
    final var snapshot = projects.checkout(svc.sourceProject(), revision);
    final Map<String, String> values = new TreeMap<>();
    snapshot.files().forEach((final var path, final var encoded) -> {
      if (
        path.substring(path.lastIndexOf('/') + 1).matches("values[^/]*\\.ya?ml") &&
        !path.contains("/templates/")
      ) {
        final byte[] bytes = Base64.getDecoder().decode(encoded);
        if (bytes.length <= 65536) values.put(path, new String(bytes, StandardCharsets.UTF_8));
      }
    });
    return Map.of("commit", snapshot.commit(), "valuesFiles", values);
  }

  public Plan prepare(final Request request) {
    return prepare(request, (final var service, final var stage) -> {});
  }

  public Plan prepare(
    final Request request,
    final java.util.function.BiConsumer<String, String> progress
  ) {
    enabled();
    if (
      request == null ||
      request.name() == null ||
      request.name().isBlank() ||
      request.name().length() > 100 ||
      request.loadGenerator() == null
    ) throw Problem.invalid("profile", "Name and load-generator service are required");
    if (
      request.services() == null ||
      request.services().size() > 30 ||
      request.warmupSeconds() < 0 ||
      request.measurementSeconds() < 1 ||
      request.maxRunDurationSeconds() < 60 ||
      request.maxRunDurationSeconds() > settings.maxRunDurationSeconds ||
      (long) request.warmupSeconds() + request.measurementSeconds() >=
        request.maxRunDurationSeconds()
    ) throw Problem.invalid(
      "duration",
      "Use a run limit between 60 and " +
        settings.maxRunDurationSeconds +
        " seconds with room for deployment, warmup and measurement"
    );
    progress.accept(null, "Checking cluster connection");
    final String environment =
      request.targetEnvironment() == null || request.targetEnvironment().isBlank()
        ? catalog.boundEnvironment()
        : catalog.selectedEnvironment(request.targetEnvironment());
    final var target = helm.target(environment);
    final var selections = new LinkedHashMap<String, Deployment>();
    for (final var selection : request.services())
      if (
        selection == null || selections.put(selection.serviceId(), selection) != null
      ) throw Problem.invalid("services", "Duplicate service");
    if (selections.containsKey(request.loadGenerator().serviceId())) throw Problem.invalid(
      "loadGenerator",
      "Load generator must be separate from deployed services"
    );
    final List<PreparedService> prepared = new ArrayList<>();
    final Map<String, String> charts = new java.util.concurrent.ConcurrentHashMap<>();
    final Set<String> destinations = java.util.concurrent.ConcurrentHashMap.newKeySet();
    final var all = new ArrayList<>(selections.values());
    all.add(request.loadGenerator());
    progress.accept(null, "Preparing service charts in parallel");
    final var attributes =
      org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
    try (
      var executor = java.util.concurrent.Executors.newFixedThreadPool(
        settings.preparationConcurrency
      )
    ) {
      final var completed = new java.util.concurrent.ExecutorCompletionService<
        Map.Entry<Integer, PreparedService>
      >(executor);
      final var futures = new ArrayList<
        java.util.concurrent.Future<Map.Entry<Integer, PreparedService>>
      >();
      try {
        for (int index = 0; index < all.size(); index++) {
          final int position = index;
          final var selection = all.get(index);
          futures.add(
            completed.submit(
              com.example.perforchestrator.infrastructure.diagnostics.DiagnosticLog.propagate(
                () -> {
                  preparationSlots.acquire();
                  org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                    attributes
                  );
                  try {
                    progress.accept(selection.serviceId(), "Checking Helm release");
                    final var service = prepareService(
                      selection,
                      target,
                      charts,
                      destinations,
                      progress,
                      null,
                      environment
                    );
                    if (
                      position == all.size() - 1 && !service.baselineDigest().equals("ABSENT")
                    ) throw Problem.conflict(
                      "Load-generator release already exists; use a dedicated uninstalled release before preparing a run"
                    );
                    progress.accept(selection.serviceId(), "Ready");
                    return Map.entry(position, service);
                  } catch (final RuntimeException error) {
                    progress.accept(selection.serviceId(), "Failed");
                    throw error;
                  } finally {
                    org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
                    preparationSlots.release();
                  }
                }
              )
            )
          );
        }
        final var ordered = new PreparedService[all.size()];
        for (int index = 0; index < all.size(); index++) {
          final var result = completed.take().get();
          ordered[result.getKey()] = result.getValue();
        }
        prepared.addAll(Arrays.asList(ordered));
      } catch (final InterruptedException error) {
        Thread.currentThread().interrupt();
        throw Problem.conflict("Review interrupted; prepare again");
      } catch (final java.util.concurrent.ExecutionException error) {
        if (error.getCause() instanceof final RuntimeException cause) throw cause;
        throw Problem.conflict("Review failed; prepare again");
      } finally {
        futures.forEach((final var future) -> future.cancel(true));
        executor.shutdownNow();
      }
    }
    final var load = prepared.getLast();
    progress.accept(null, "Validating monitoring and saving review");
    final var metrics =
      request.metrics() == null ? List.<Metric>of() : List.copyOf(request.metrics());
    final Set<String> names = new HashSet<>();
    if (metrics.size() > 20) throw Problem.invalid("metrics", "At most 20 queries are supported");
    for (final var metric : metrics) {
      if (
        metric == null ||
        metric.name() == null ||
        !metric.name().matches("[a-z][a-z0-9_]{0,63}") ||
        !names.add(metric.name()) ||
        metric.query() == null ||
        metric.query().isBlank() ||
        metric.query().length() > 8192 ||
        !catalog.data().services().containsKey(metric.serviceId())
      ) throw Problem.invalid(
        "metrics",
        "Supply unique named LogQL queries for configured services"
      );
      RunMonitoring.validate(metric, catalog, connections);
    }
    final var thresholds =
      request.thresholds() == null ? List.<Threshold>of() : List.copyOf(request.thresholds());
    for (final var threshold : thresholds)
      if (
        threshold == null ||
        !names.contains(threshold.metric()) ||
        !Double.isFinite(threshold.maximum())
      ) throw Problem.invalid(
        "thresholds",
        "Thresholds must reference a configured metric and finite maximum"
      );
    final Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("target", target);
    meta.put("charts", charts);
    meta.put("loadService", load.serviceId());
    meta.put("metrics", metrics);
    meta.put("connectionHash", Json.hash(Json.write(connections.data())));
    final var profile = new Profile(
      request.name(),
      environment,
      prepared
        .stream()
        .map((final var s) ->
          new Selection(
            s.serviceId(),
            Action.DEPLOY,
            new Build(s.image().sourceRef(), null, s.image().version()),
            ""
          )
        )
        .toList(),
      new Load("real-yaml", 0, 0, request.warmupSeconds(), request.measurementSeconds(), ""),
      request.maxRunDurationSeconds(),
      thresholds
    );
    final Instant now = Instant.now();
    var plan = new Plan(
      UUID.randomUUID().toString(),
      "",
      now.toString(),
      now.plusSeconds(900).toString(),
      com.example.perforchestrator.infrastructure.secrets.SecretServerTokens.currentActor(),
      null,
      0,
      profile,
      catalog.hash(),
      catalog.environment(environment).clusterIdentity(),
      load.namespace(),
      load.sourceRevision(),
      Map.copyOf(meta),
      List.copyOf(prepared),
      List.of(
        "This plan executes Helm in the configured cluster.",
        "Only ckp is checked out; Helm dependencies are resolved during preparation and frozen in the reviewed snapshot.",
        "imageTag/global.imageTag exactly match the selected version; digest is checked again at submission but deployment uses the tag.",
        "Load release must be absent; Stop uninstalls only this run's load release. Service deployments remain.",
        "Helm readiness uses --wait for services. Chart hooks and load-generator behavior are defined by the selected chart.",
        "Load rates and tool duration come from values YAML; orchestrator timing independently bounds the run.",
        metrics.isEmpty()
          ? "Metrics not configured: performance verdict will be INCONCLUSIVE."
          : "Metrics use supplied LogQL; confirm event counts correspond to messages."
      )
    );
    plan = new Plan(
      plan.id(),
      PlanChecksums.checksum(plan),
      plan.createdAt(),
      plan.expiresAt(),
      plan.actor(),
      null,
      0,
      plan.profile(),
      plan.catalogHash(),
      plan.clusterIdentity(),
      plan.loadNamespace(),
      plan.scenarioRevision(),
      plan.effectiveLoadConfiguration(),
      plan.services(),
      plan.warnings()
    );
    store.plan(plan);
    return plan;
  }

  public record AdditionalPreparation(PreparedService service, String chart) {}

  public AdditionalPreparation prepareAdditional(
    final Deployment selection,
    final Map<String, Object> target,
    final String releaseName
  ) {
    return prepareAdditional(selection, target, releaseName, catalog.boundEnvironment());
  }

  public AdditionalPreparation prepareAdditional(
    final Deployment selection,
    final Map<String, Object> target,
    final String releaseName,
    final String environment
  ) {
    enabled();
    if (selection == null || selection.serviceId() == null) throw Problem.invalid(
      "loadGenerator",
      "Choose a load generator"
    );
    if (!helm.target(environment).equals(target)) throw Problem.conflict(
      "Cluster target changed since this run started"
    );
    final var charts = new HashMap<String, String>();
    final var service = prepareService(
      selection,
      target,
      charts,
      new HashSet<>(),
      (final var id, final var stage) -> {},
      releaseName,
      environment
    );
    if (!"ABSENT".equals(service.baselineDigest())) throw Problem.conflict(
      "Additional load release already exists; prepare again"
    );
    return new AdditionalPreparation(service, charts.get(service.serviceId()));
  }

  private PreparedService prepareService(
    final Deployment selection,
    final Map<String, Object> target,
    final Map<String, String> charts,
    final Set<String> destinations,
    final java.util.function.BiConsumer<String, String> progress,
    final String releaseName,
    final String environment
  ) {
    final var service = catalog.service(selection.serviceId());
    var destination = service
      .deploymentByEnvironment()
      .getOrDefault(environment, service.deploymentDefaults());
    if (
      destination == null ||
      !destination.namespace().matches("[a-z0-9][a-z0-9-]{0,62}") ||
      !destination.releaseName().matches("[a-z0-9][a-z0-9-]{0,52}")
    ) throw Problem.invalid("deployment", "Configure a valid namespace and Helm release");
    if (releaseName != null) destination = new Catalog.Destination(
      destination.namespace(),
      releaseName,
      destination.valuesFiles()
    );
    if (
      !destinations.add(destination.namespace() + "/" + destination.releaseName())
    ) throw Problem.invalid("deployment", "Two services cannot share a Helm release");
    if (service.sourceProject() == null || service.containerImage() == null) throw Problem.invalid(
      "service",
      "Configure Git and container image mappings"
    );
    final String chart = service.sourceProject().chartPath();
    ConnectionConfigSafe(chart);
    final String baseline;
    try {
      baseline = helm.baseline(target, destination.namespace(), destination.releaseName());
    } catch (final Problem error) {
      throw new Problem(
        error.status(),
        error.code(),
        error.field(),
        "Service " +
          selection.serviceId() +
          ", namespace " +
          destination.namespace() +
          ", release " +
          destination.releaseName() +
          ", context " +
          target.get("context") +
          ": " +
          error.getMessage()
      );
    }
    progress.accept(selection.serviceId(), "Checking out CKP files");
    final var snapshot = projects.checkout(service.sourceProject(), selection.revision());
    if (!snapshot.files().containsKey(chart + "/Chart.yaml")) throw Problem.invalid(
      "chartPath",
      "Chart.yaml is missing from the sparse CKP snapshot"
    );
    progress.accept(selection.serviceId(), "Resolving selected image");
    final var image = images.resolve(
      selection.serviceId(),
      "service:" + selection.serviceId(),
      null,
      selection.imageVersion()
    );
    var preparedFiles = ChartVersions.prepare(snapshot.files(), chart, image.version());
    final List<String> values =
      selection.valuesFiles() == null || selection.valuesFiles().isEmpty()
        ? destination.valuesFiles()
        : selection.valuesFiles();
    final var edits =
      selection.valuesEdits() == null ? Map.<String, String>of() : selection.valuesEdits();
    if (!values.containsAll(edits.keySet())) throw Problem.invalid(
      "valuesEdits",
      "Edits must belong to selected values files"
    );
    Map<String, Object> effective = new TreeMap<>();
    for (final String path : values) {
      ConnectionConfigSafe(path);
      final var encoded = snapshot.files().get(path);
      if (encoded == null) throw Problem.invalid(
        "valuesFiles",
        "Selected values file is absent from the checked-out revision"
      );
      final String content = edits.containsKey(path)
        ? edits.get(path)
        : new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
      if (content == null) throw Problem.invalid("valuesEdits", "Edited YAML content is required");
      effective = HelmValues.merge(effective, HelmValues.parse(content));
    }
    final var base = new TreeMap<>(effective);
    final String clusterSubdomain = catalog.environment(environment).clusterIdentity();
    // Apply CKP defaults below user-selected values so explicit per-service edits survive.
    effective = HelmValues.merge(
      Map.of(
        "clusterSubdomain",
        clusterSubdomain,
        "tags",
        Map.of("moc", false),
        "global",
        Map.of("clusterSubdomain", clusterSubdomain, "deploymentSuffix", "")
      ),
      effective
    );
    effective = HelmValues.merge(effective, HelmValues.parse(selection.overlay()));
    // Platform, environment and selected image are controlled by the orchestrator.
    effective.put("targetPlatform", "ckp");
    effective.put("imageName", service.containerImage().imageName());
    effective.put("imageTag", image.version());
    effective = HelmValues.merge(
      effective,
      Map.of(
        "global",
        Map.of("imageTag", image.version(), "environment", environment, "targetPlatform", "ckp")
      )
    );
    Path folder = null;
    try {
      folder = Files.createTempDirectory(settings.workspace, "prepare-");
      SparseProjects.materialize(folder, preparedFiles);
      progress.accept(selection.serviceId(), "Downloading Helm dependencies");
      helm.prepareDependencies(folder, chart);
      preparedFiles = SparseProjects.snapshot(folder);
      Files.writeString(folder.resolve("effective-values.json"), Json.write(effective));
      progress.accept(selection.serviceId(), "Validating chart and values");
      helm.validate(
        folder,
        chart,
        destination.namespace(),
        destination.releaseName(),
        service.containerImage().imageName() + ":" + image.version()
      );
    } catch (final Problem error) {
      throw new Problem(
        error.status(),
        error.code(),
        error.field(),
        "Service " +
          selection.serviceId() +
          ", chart " +
          chart +
          ", namespace " +
          destination.namespace() +
          ", values " +
          String.join(", ", values) +
          ": " +
          error.getMessage()
      );
    } catch (final java.io.IOException e) {
      throw Problem.invalid(
        "source",
        "Unable to prepare chart files for service " + selection.serviceId()
      );
    } finally {
      if (folder != null) SparseProjects.delete(folder);
    }
    final var hashes = new TreeMap<String, String>();
    snapshot
      .files()
      .forEach((final var path, final var content) -> hashes.put(path, Json.hash(content)));
    final var preparedHashes = new TreeMap<String, String>();
    preparedFiles.forEach((final var path, final var content) ->
      preparedHashes.put(path, Json.hash(content))
    );
    charts.put(selection.serviceId(), chart);
    return new PreparedService(
      selection.serviceId(),
      Action.DEPLOY,
      destination.namespace(),
      destination.releaseName(),
      image,
      baseline,
      snapshot.commit(),
      hashes,
      preparedFiles,
      preparedHashes,
      ImmutableConfiguration.values(effective),
      HelmValues.diff(base, effective),
      List.of()
    );
  }

  private static void ConnectionConfigSafe(final String path) {
    com.example.perforchestrator.infrastructure.registry.ConnectionConfig.safePath(path);
    if (!path.startsWith("ckp/")) throw Problem.invalid(
      "path",
      "Chart and values paths must be within ckp/"
    );
  }
}

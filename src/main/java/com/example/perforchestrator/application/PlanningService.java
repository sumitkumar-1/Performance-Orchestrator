package com.example.perforchestrator.application;

import static com.example.perforchestrator.domain.Model.*;

import com.example.perforchestrator.domain.*;
import com.example.perforchestrator.infrastructure.config.*;
import com.example.perforchestrator.infrastructure.persistence.Store;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanningService {
  private final Catalog catalog;
  private final Ports.ImageResolver images;
  private final Ports.DeploymentGateway deployments;
  private final Store store;

  public PlanningService(
      Catalog catalog,
      Ports.ImageResolver images,
      Ports.DeploymentGateway deployments,
      Store store) {
    this.catalog = catalog;
    this.images = images;
    this.deployments = deployments;
    this.store = store;
  }

  public void validate(Profile p) {
    catalog.requireExecution();
    if (p == null) throw Problem.invalid("profile", "Profile is required");
    if (p.name() == null || p.name().isBlank() || p.name().length() > 100)
      throw Problem.invalid("name", "Name must contain 1–100 characters");
    var env = catalog.environment(p.targetEnvironment());
    if (!env.allowedActions().containsAll(List.of("PLAN", "RUN_LOAD")))
      throw Problem.invalid(
          "targetEnvironment", "Environment disallows planning or load execution");
    if (p.services() == null || p.services().isEmpty() || p.services().size() > 50)
      throw Problem.invalid("services", "Choose between 1 and 50 services");
    Set<String> selected = new HashSet<>();
    for (var s : p.services()) {
      if (s == null || s.serviceId() == null || !selected.add(s.serviceId()))
        throw Problem.invalid("services", "Services must be unique");
      var svc = catalog.service(s.serviceId());
      var dest = svc.deploymentByEnvironment().get(p.targetEnvironment());
      if (dest == null || !env.serviceNamespaces().contains(dest.namespace()))
        throw Problem.invalid("services", "Service namespace is not mapped or allowed");
      if (s.action() == null
          || (s.action() == Action.DEPLOY && !env.allowedActions().contains("DEPLOY")))
        throw Problem.invalid("services.action", "Deployment action is not permitted");
      images.resolve(s.serviceId(), s.build());
      YamlValues.allowed(
          YamlValues.parse(s.valuesOverlay()), Set.copyOf(svc.allowedOverridePaths()), "");
    }
    var l = p.loadGenerator();
    if (l == null || !catalog.data().scenarios().containsKey(l.templateRef()))
      throw Problem.invalid("loadGenerator.templateRef", "Choose a registered scenario");
    if (l.virtualUsers() < 1 || l.virtualUsers() > env.limits().maxVirtualUsers())
      throw Problem.invalid("loadGenerator.virtualUsers", "Virtual users exceed allowed bounds");
    if (l.requestsPerSecond() < 1 || l.requestsPerSecond() > env.limits().maxRequestsPerSecond())
      throw Problem.invalid(
          "loadGenerator.requestsPerSecond", "Request rate exceeds allowed bounds");
    if (p.maxRunDurationSeconds() < 15
        || p.maxRunDurationSeconds() > env.limits().maxRunDurationSeconds())
      throw Problem.invalid(
          "maxRunDurationSeconds", "Run duration must be between 15 and the environment limit");
    if (l.warmupSeconds() < 0
        || l.measurementSeconds() < 1
        || (long) l.warmupSeconds() + l.measurementSeconds() + 10 > p.maxRunDurationSeconds())
      throw Problem.invalid(
          "loadGenerator.measurementSeconds",
          "Measurement and warmup must fit inside the run limit with 10 seconds for orchestration");
    YamlValues.allowed(
        YamlValues.parse(l.configurationOverlay()),
        Set.copyOf(catalog.data().scenarios().get(l.templateRef()).allowedOverridePaths()),
        "");
    if (p.simulationCase() == null)
      throw Problem.invalid("simulationCase", "Choose a simulation case");
    if (p.thresholds() == null || p.thresholds().size() > 20)
      throw Problem.invalid("thresholds", "Supply up to 20 thresholds");
    Set<String> thresholdNames = new HashSet<>();
    for (var t : p.thresholds())
      if (t == null
          || t.metric() == null
          || !Set.of("latency_p95_ms", "error_rate", "throughput_rps", "request_count")
              .contains(t.metric())
          || !Double.isFinite(t.maximum())
          || t.maximum() < 0
          || !thresholdNames.add(t.metric()))
        throw Problem.invalid(
            "thresholds",
            "Thresholds must use unique supported metrics and finite nonnegative maxima");
  }

  @Transactional
  public SavedProfile save(String id, Integer revision, Profile profile) {
    validate(profile);
    return store.save(id, revision, profile);
  }

  @Transactional
  public Plan create(String profileId, Integer revision, Profile inline) {
    catalog.requireExecution();
    Profile profile = inline;
    int resolvedRevision = 0;
    if (profileId != null) {
      if (inline != null)
        throw Problem.invalid("profile", "Use profileId or inline profile, not both");
      var saved = store.profile(profileId);
      if (revision != null && revision != saved.revision())
        throw Problem.conflict("Profile revision changed. Reload before planning.");
      profile = saved.profile();
      resolvedRevision = saved.revision();
    }
    validate(profile);
    var env = catalog.environment(profile.targetEnvironment());
    List<PreparedService> prepared = new ArrayList<>();
    for (var selection : profile.services()) prepared.add(prepare(profile, selection));
    var scenario = catalog.data().scenarios().get(profile.loadGenerator().templateRef());
    var effectiveLoad =
        YamlValues.merge(
            scenario.defaults(), YamlValues.parse(profile.loadGenerator().configurationOverlay()));
    effectiveLoad.put("namespace", env.loadGeneratorNamespace());
    effectiveLoad.put("virtualUsers", profile.loadGenerator().virtualUsers());
    effectiveLoad.put("requestsPerSecond", profile.loadGenerator().requestsPerSecond());
    effectiveLoad.put("warmupSeconds", profile.loadGenerator().warmupSeconds());
    effectiveLoad.put("measurementSeconds", profile.loadGenerator().measurementSeconds());
    var warnings =
        List.of(
            "SIMULATION: no CKP, registry or load-generator requests are made by this plan.",
            "Fixture file hashes identify configuration snapshots; these are not Git commits.",
            "Helm rendering, admission, quota, pull access and network reachability are unavailable"
                + " in simulation.",
            "Service versions are kept after runs. Load resources are stopped; no automatic"
                + " restoration.");
    Instant now = Instant.now();
    var plan =
        new Plan(
            UUID.randomUUID().toString(),
            "",
            now.toString(),
            now.plusSeconds(900).toString(),
            "local-developer",
            profileId,
            resolvedRevision,
            profile,
            catalog.hash(),
            env.clusterIdentity(),
            env.loadGeneratorNamespace(),
            scenario.revision(),
            Map.copyOf(effectiveLoad),
            List.copyOf(prepared),
            warnings,
            true);
    plan =
        new Plan(
            plan.id(),
            checksum(plan),
            plan.createdAt(),
            plan.expiresAt(),
            plan.actor(),
            plan.profileId(),
            plan.profileRevision(),
            plan.profile(),
            plan.catalogHash(),
            plan.clusterIdentity(),
            plan.loadNamespace(),
            plan.scenarioRevision(),
            plan.effectiveLoadConfiguration(),
            plan.services(),
            plan.warnings(),
            true);
    store.plan(plan);
    return plan;
  }

  public static String checksum(Plan p) {
    return Json.hash(
        Json.write(
            new Plan(
                p.id(),
                "",
                p.createdAt(),
                p.expiresAt(),
                p.actor(),
                p.profileId(),
                p.profileRevision(),
                p.profile(),
                p.catalogHash(),
                p.clusterIdentity(),
                p.loadNamespace(),
                p.scenarioRevision(),
                p.effectiveLoadConfiguration(),
                p.services(),
                p.warnings(),
                p.simulated())));
  }

  private PreparedService prepare(Profile profile, Selection selection) {
    var svc = catalog.service(selection.serviceId());
    var binding = svc.installationBindings();
    var destination = svc.deploymentByEnvironment().get(profile.targetEnvironment());
    var image = images.resolve(selection.serviceId(), selection.build());
    if (!image.version().matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}"))
      throw Problem.invalid("version", "Unsupported fixture version format");
    Map<String, String> originals = new TreeMap<>(), files = new TreeMap<>();
    String chart = catalog.read(svc, binding.file());
    originals.put(binding.file(), Json.hash(chart));
    long count = chart.lines().filter(line -> line.equals(binding.exactLine())).count();
    if (count != binding.expectedOccurrences() || !binding.exactLine().contains("REPLACE_VERSION"))
      throw Problem.invalid(
          "installationBindings",
          "Chart placeholder occurrence count does not match registered binding");
    String modified =
        chart
            .lines()
            .map(
                line ->
                    line.equals(binding.exactLine())
                        ? line.replace("REPLACE_VERSION", image.version())
                        : line)
            .reduce("", (a, b) -> a + b + "\n");
    if (modified.contains("REPLACE_VERSION"))
      throw Problem.invalid("installationBindings", "Unregistered placeholder remains unresolved");
    files.put(binding.file(), modified);
    Map<String, Object> base = new TreeMap<>();
    for (String file : destination.valuesFiles()) {
      String content = catalog.read(svc, file);
      originals.put(file, Json.hash(content));
      files.put(file, content);
      base = YamlValues.merge(base, YamlValues.parse(content));
    }
    var effective = YamlValues.merge(base, YamlValues.parse(selection.valuesOverlay()));
    effective.put(
        "image",
        Map.of("repository", image.repository(), "tag", image.version(), "digest", image.digest()));
    effective.put(binding.selectorKey(), binding.sourceSelectors().get(image.sourceRef()));
    files.put("generated-values.yaml", Json.write(effective));
    Map<String, String> hashes = new TreeMap<>();
    files.forEach((name, content) -> hashes.put(name, Json.hash(content)));
    String baseline =
        deployments.baseline(
            catalog.environment(profile.targetEnvironment()).clusterIdentity(),
            destination.namespace(),
            selection.serviceId());
    if (selection.action() == Action.VERIFY_EXISTING && !baseline.equals(image.digest()))
      throw Problem.invalid(
          "services.action",
          "Existing simulated image does not match the selected digest for "
              + selection.serviceId());
    return new PreparedService(
        selection.serviceId(),
        selection.action(),
        destination.namespace(),
        destination.releaseName(),
        image,
        baseline,
        "fixture-sha256:" + Json.hash(Json.write(originals)),
        Map.copyOf(originals),
        Map.copyOf(files),
        Map.copyOf(hashes),
        Map.copyOf(effective),
        YamlValues.diff(base, effective),
        List.of(
            "--set-string",
            binding.helmValuesKey() + "=" + image.version(),
            "--set-string",
            binding.selectorKey() + "=" + binding.sourceSelectors().get(image.sourceRef())));
  }
}

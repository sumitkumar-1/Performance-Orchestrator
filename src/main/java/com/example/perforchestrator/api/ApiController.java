package com.example.perforchestrator.api;

import static com.example.perforchestrator.domain.Model.*;

import com.example.perforchestrator.application.*;
import com.example.perforchestrator.domain.*;
import com.example.perforchestrator.infrastructure.config.Catalog;
import com.example.perforchestrator.infrastructure.config.Json;
import com.example.perforchestrator.infrastructure.persistence.Store;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ApiController {
  public record ProfileInput(Integer revision, Profile profile) {}

  public record PlanInput(String profileId, Integer revision, Profile profile) {}

  private final Catalog catalog;
  private final Optional<Ports.ImageDiscovery> images;
  private final Store store;
  private final PlanningService planning;
  private final RunService runs;
  private final Reports reports;
  private final WorkflowWorker worker;

  public ApiController(
      Catalog catalog,
      Optional<Ports.ImageDiscovery> images,
      Store store,
      PlanningService planning,
      RunService runs,
      Reports reports,
      WorkflowWorker worker) {
    this.catalog = catalog;
    this.images = images;
    this.store = store;
    this.planning = planning;
    this.runs = runs;
    this.reports = reports;
    this.worker = worker;
  }

  @GetMapping("/session")
  public Object session(CsrfToken token) {
    return Map.of(
        "actor",
        "local-developer",
        "mode",
        catalog.mode(),
        "targetEnvironment", catalog.boundEnvironment(),
        "capabilities",
        Map.of(
            "execution",
            catalog.mode().equals("simulation"),
            "realRegistryDiscovery",
            true,
            "realSecretRetrieval",
            true,
            "liveMetrics",
            false),
        "csrfToken",
        token.getToken(),
        "csrfHeader",
        token.getHeaderName());
  }

  @GetMapping("/environments")
  public Object environments() {
    Map<String, Object> result = new TreeMap<>();
    catalog
        .data()
        .environments()
        .forEach(
            (id, env) ->
                result.put(
                    id,
                    Map.of(
                        "configuration",
                        env,
                        "available",
                        store.environmentAvailable(env.clusterIdentity() + "/" + id))));
    return result;
  }

  @GetMapping("/services")
  public Object services() {
    return catalog.data().services();
  }

  @GetMapping("/image-sources")
  public Object sources() {
    return catalog.data().imageSources();
  }

  @GetMapping("/scenarios")
  public Object scenarios() {
    return catalog.data().scenarios();
  }

  @GetMapping("/services/{id}/images")
  public Object images(
      @PathVariable String id,
      @RequestParam String source,
      @RequestParam(required = false) String username,
      @RequestParam(defaultValue = "") String query,
      @RequestParam(defaultValue = "0") int cursor,
      @RequestParam(defaultValue = "50") int limit) {
    if (cursor < 0 || limit < 1 || limit > 100)
      throw Problem.invalid("cursor", "Invalid pagination");
    var all =
        images
            .orElseThrow(
                () ->
                    new Problem(
                        422,
                        "USE_REGISTRY_DISCOVERY",
                        "source",
                        "Use Connections & catalog for paginated real registry discovery"))
            .discover(id, source, username)
            .stream()
            .filter(i -> i.version().contains(query))
            .toList();
    var page = all.stream().skip(cursor).limit(limit).toList();
    return Map.of(
        "items",
        page,
        "nextCursor",
        cursor + page.size() < all.size() ? String.valueOf(cursor + page.size()) : "",
        "discoveredAt",
        Instant.now().toString(),
        "simulated",
        catalog.mode().equals("simulation"),
        "ordering",
        "tag descending lexicographic; not publication order");
  }

  @GetMapping("/profiles")
  public Object profiles() {
    return store.profiles();
  }

  @GetMapping("/profiles/{id}")
  public Object profile(@PathVariable String id) {
    return store.profile(id);
  }

  @PostMapping("/profiles")
  @ResponseStatus(HttpStatus.CREATED)
  public Object create(@RequestBody ProfileInput input) {
    return planning.save(null, null, input.profile());
  }

  @PutMapping("/profiles/{id}")
  public Object update(@PathVariable String id, @RequestBody ProfileInput input) {
    return planning.save(id, input.revision(), input.profile());
  }

  @PostMapping("/plans")
  @ResponseStatus(HttpStatus.CREATED)
  public Object plan(@RequestBody PlanInput input) {
    return planning.create(input.profileId(), input.revision(), input.profile());
  }

  @GetMapping("/plans/{id}")
  public Object plan(@PathVariable String id) {
    return store.plan(id);
  }

  @PostMapping("/runs")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Object run(
      @RequestHeader("Idempotency-Key") String key, @RequestBody RunService.Submission input) {
    return runs.enqueue(key, input);
  }

  @GetMapping("/runs")
  public Object runs(
      @RequestParam(required = false) String environment,
      @RequestParam(required = false) String profileId,
      @RequestParam(required = false) String outcome,
      @RequestParam(required = false) String version,
      @RequestParam(required = false) String since,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "50") int limit) {
    if (offset < 0 || limit < 1 || limit > 100)
      throw Problem.invalid("limit", "Invalid pagination");
    return store.runs().stream()
        .filter(r -> environment == null || r.environment().equals(environment))
        .filter(r -> outcome == null || r.state().name().equals(outcome))
        .filter(r -> since == null || Instant.parse(r.createdAt()).isAfter(Instant.parse(since)))
        .filter(r -> profileId == null || profileId.equals(store.plan(r.planId()).profileId()))
        .filter(
            r ->
                version == null
                    || store.plan(r.planId()).services().stream()
                        .anyMatch(s -> s.image().version().equals(version)))
        .skip(offset)
        .limit(limit)
        .toList();
  }

  @GetMapping("/runs/{id}")
  public Object run(@PathVariable String id) {
    return store.run(id);
  }

  @GetMapping(value = "/runs/{id}/events", produces = MediaType.APPLICATION_JSON_VALUE)
  public Object events(@PathVariable String id, @RequestParam(defaultValue = "0") long after) {
    store.run(id);
    return store.events(id, after);
  }

  @GetMapping(value = "/runs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public String stream(
      @PathVariable String id,
      @RequestHeader(value = "Last-Event-ID", defaultValue = "0") long last,
      @RequestParam(defaultValue = "0") long after) {
    store.run(id);
    StringBuilder stream = new StringBuilder("retry: 2000\n\n");
    for (var event : store.events(id, Math.max(last, after)))
      stream
          .append("id: ")
          .append(event.id())
          .append("\ndata: ")
          .append(Json.write(event))
          .append("\n\n");
    return stream.toString();
  }

  @PostMapping("/runs/{id}/cancel")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Object cancel(@PathVariable String id) {
    return runs.cancel(id);
  }

  @PostMapping("/runs/{id}/recover")
  public Object recover(@PathVariable String id) {
    return worker.recover(id);
  }

  @GetMapping(value = "/runs/{id}/report", produces = MediaType.TEXT_HTML_VALUE)
  public String report(@PathVariable String id) {
    return reports.html(id);
  }

  @GetMapping("/runs/{id}/artifacts")
  public Object artifacts(@PathVariable String id) {
    return reports.artifacts(id);
  }

  @GetMapping("/runs/{id}/artifacts/{name}")
  public ResponseEntity<String> download(@PathVariable String id, @PathVariable String name) {
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\""
                + (name.equals("report.html") ? "report.html" : "summary.json")
                + "\"")
        .contentType(name.equals("report.html") ? MediaType.TEXT_HTML : MediaType.APPLICATION_JSON)
        .body(reports.download(id, name));
  }
}

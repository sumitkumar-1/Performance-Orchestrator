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
  private final com.example.perforchestrator.infrastructure.execution.ExecutionSettings execution;
  private final Optional<Ports.ImageDiscovery> images;
  private final Store store;
  private final PlanningService planning;
  private final RunService runs;
  private final Reports reports;
  private final WorkflowWorker worker;

  public ApiController(
    final Catalog catalog,
    final Optional<Ports.ImageDiscovery> images,
    final Store store,
    final PlanningService planning,
    final RunService runs,
    final Reports reports,
    final WorkflowWorker worker,
    final com.example.perforchestrator.infrastructure.execution.ExecutionSettings execution
  ) {
    this.execution = execution;
    this.catalog = catalog;
    this.images = images;
    this.store = store;
    this.planning = planning;
    this.runs = runs;
    this.reports = reports;
    this.worker = worker;
  }

  @GetMapping("/session")
  public Object session(
    final CsrfToken token,
    final jakarta.servlet.http.HttpServletRequest request
  ) {
    return Map.of(
      "actor",
      com.example.perforchestrator.infrastructure.secrets.SecretServerTokens.currentActor(),
      "mode",
      catalog.mode(),
      "styleNonce",
      EditorStyles.nonce(request),
      "targetEnvironment",
      catalog.boundEnvironment(),
      "capabilities",
      Map.of(
        "execution",
        catalog.mode().equals("simulation") || execution.enabled,
        "realRegistryDiscovery",
        true,
        "realSecretRetrieval",
        true,
        "liveMetrics",
        execution.enabled
      ),
      "csrfToken",
      token.getToken(),
      "csrfHeader",
      token.getHeaderName()
    );
  }

  @GetMapping("/environments")
  public Object environments() {
    final Map<String, Object> result = new TreeMap<>();
    catalog
      .data()
      .environments()
      .forEach((final var id, final var env) ->
        result.put(
          id,
          Map.of(
            "configuration",
            env,
            "available",
            store.environmentAvailable(env.clusterIdentity() + "/" + id)
          )
        )
      );
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
    final @PathVariable String id,
    final @RequestParam String source,
    final @RequestParam(required = false) String username,
    final @RequestParam(defaultValue = "") String query,
    final @RequestParam(defaultValue = "0") int cursor,
    final @RequestParam(defaultValue = "50") int limit
  ) {
    if (cursor < 0 || limit < 1 || limit > 100) throw Problem.invalid(
      "cursor",
      "Invalid pagination"
    );
    final var all = images
      .orElseThrow(() ->
        new Problem(
          422,
          "USE_REGISTRY_DISCOVERY",
          "source",
          "Use Connections & catalog for paginated real registry discovery"
        )
      )
      .discover(id, source, username)
      .stream()
      .filter((final var i) -> i.version().contains(query))
      .toList();
    final var page = all.stream().skip(cursor).limit(limit).toList();
    return Map.of(
      "items",
      page,
      "nextCursor",
      cursor + page.size() < all.size() ? String.valueOf(cursor + page.size()) : "",
      "discoveredAt",
      Instant.now().toString(),
      "simulated",
      catalog.mode().equals("simulation") || execution.enabled,
      "ordering",
      "tag descending lexicographic; not publication order"
    );
  }

  @GetMapping("/profiles")
  public Object profiles() {
    return store.profiles();
  }

  @GetMapping("/profiles/{id}")
  public Object profile(final @PathVariable String id) {
    return store.profile(id);
  }

  @PostMapping("/profiles")
  @ResponseStatus(HttpStatus.CREATED)
  public Object create(final @RequestBody ProfileInput input) {
    return planning.save(null, null, input.profile());
  }

  @PutMapping("/profiles/{id}")
  public Object update(final @PathVariable String id, final @RequestBody ProfileInput input) {
    return planning.save(id, input.revision(), input.profile());
  }

  @PostMapping("/plans")
  @ResponseStatus(HttpStatus.CREATED)
  public Object plan(final @RequestBody PlanInput input) {
    return planning.create(input.profileId(), input.revision(), input.profile());
  }

  @GetMapping("/plans/{id}")
  public Object plan(final @PathVariable String id) {
    return store.plan(id);
  }

  @PostMapping("/runs")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Object run(
    final @RequestHeader("Idempotency-Key") String key,
    final @RequestBody RunService.Submission input
  ) {
    return runs.enqueue(key, input);
  }

  @GetMapping("/runs")
  public Object runs(
    final @RequestParam(required = false) String environment,
    final @RequestParam(required = false) String profileId,
    final @RequestParam(required = false) String outcome,
    final @RequestParam(required = false) String version,
    final @RequestParam(required = false) String since,
    final @RequestParam(defaultValue = "0") int offset,
    final @RequestParam(defaultValue = "50") int limit
  ) {
    if (offset < 0 || limit < 1 || limit > 100) throw Problem.invalid(
      "limit",
      "Invalid pagination"
    );
    return store
      .runs()
      .stream()
      .filter((final var r) -> environment == null || r.environment().equals(environment))
      .filter((final var r) -> outcome == null || r.state().name().equals(outcome))
      .filter(
        (final var r) -> since == null || Instant.parse(r.createdAt()).isAfter(Instant.parse(since))
      )
      .filter(
        (final var r) -> profileId == null || profileId.equals(store.plan(r.planId()).profileId())
      )
      .filter(
        (final var r) ->
          version == null ||
          store
            .plan(r.planId())
            .services()
            .stream()
            .anyMatch((final var s) -> s.image().version().equals(version))
      )
      .skip(offset)
      .limit(limit)
      .toList();
  }

  @GetMapping("/runs/{id}")
  public Object run(final @PathVariable String id) {
    return store.run(id);
  }

  @GetMapping(value = "/runs/{id}/events", produces = MediaType.APPLICATION_JSON_VALUE)
  public Object events(
    final @PathVariable String id,
    final @RequestParam(defaultValue = "0") long after
  ) {
    store.run(id);
    return store.events(id, after);
  }

  @GetMapping(value = "/runs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public String stream(
    final @PathVariable String id,
    final @RequestHeader(value = "Last-Event-ID", defaultValue = "0") long last,
    final @RequestParam(defaultValue = "0") long after
  ) {
    store.run(id);
    final StringBuilder stream = new StringBuilder("retry: 2000\n\n");
    for (final var event : store.events(id, Math.max(last, after)))
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
  public Object cancel(final @PathVariable String id) {
    return runs.cancel(id);
  }

  @PostMapping("/runs/{id}/recover")
  public Object recover(final @PathVariable String id) {
    return worker.recover(id);
  }

  @GetMapping(value = "/runs/{id}/report", produces = MediaType.TEXT_HTML_VALUE)
  public String report(final @PathVariable String id) {
    return reports.html(id);
  }

  @GetMapping("/runs/{id}/artifacts")
  public Object artifacts(final @PathVariable String id) {
    return reports.artifacts(id);
  }

  @GetMapping("/runs/{id}/artifacts/{name}")
  public ResponseEntity<String> download(
    final @PathVariable String id,
    final @PathVariable String name
  ) {
    return ResponseEntity.ok()
      .header(
        HttpHeaders.CONTENT_DISPOSITION,
        "attachment; filename=\"" +
          (name.equals("report.html") ? "report.html" : "summary.json") +
          "\""
      )
      .contentType(name.equals("report.html") ? MediaType.TEXT_HTML : MediaType.APPLICATION_JSON)
      .body(reports.download(id, name));
  }
}

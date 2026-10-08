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

  private final Catalog catalog;
  private final com.example.perforchestrator.infrastructure.execution.ExecutionSettings execution;
  private final Store store;
  private final RunService runs;
  private final Reports reports;

  public ApiController(
    final Catalog catalog,
    final Store store,
    final RunService runs,
    final Reports reports,
    final com.example.perforchestrator.infrastructure.execution.ExecutionSettings execution
  ) {
    this.execution = execution;
    this.catalog = catalog;
    this.store = store;
    this.runs = runs;
    this.reports = reports;
  }

  @GetMapping("/session")
  public Object session(
    final CsrfToken token,
    final jakarta.servlet.http.HttpServletRequest request
  ) {
    return Map.of(
      "actor",
      com.example.perforchestrator.infrastructure.secrets.SecretServerTokens.currentActor(),
      "styleNonce",
      EditorStyles.nonce(request),
      "targetEnvironment",
      catalog.boundEnvironment(),
      "capabilities",
      Map.of(
        "execution",
        execution.enabled,
        "registryDiscovery",
        true,
        "secretRetrieval",
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

  @GetMapping("/scenarios")
  public Object scenarios() {
    return catalog.data().scenarios();
  }

  @GetMapping("/plans/{id}")
  public Object plan(final @PathVariable String id) {
    return store.plan(id);
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

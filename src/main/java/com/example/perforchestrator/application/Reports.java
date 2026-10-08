package com.example.perforchestrator.application;

import com.example.perforchestrator.domain.*;
import com.example.perforchestrator.infrastructure.config.Json;
import com.example.perforchestrator.infrastructure.persistence.Store;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class Reports {

  private final Store store;
  private final Ports.ArtifactStore artifacts;

  public Reports(final Store store, final Ports.ArtifactStore artifacts) {
    this.store = store;
    this.artifacts = artifacts;
  }

  public String summary(final String id) {
    final var run = store.run(id);
    final var plan = store.plan(run.planId());
    return Json.write(
      Map.of(
        "run",
        run,
        "plan",
        plan,
        "events",
        store.events(id, 0),
        "metricUnits",
        Map.of(
          "latency_p95_ms",
          "milliseconds (configured query must return these units)",
          "error_rate",
          "failed requests / measured requests",
          "throughput_rps",
          "requests/second",
          "request_count",
          "requests"
        ),
        "provenance",
        "Helm execution and configured LogQL queries; missing measurements remain unavailable",
        "cleanupPolicy",
        "KEEP services; uninstall owned load-generator release"
      )
    );
  }

  public String html(final String id) {
    return (
      "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>Performance report</title><body><h1>Performance report</h1><p>Missing measurements are unavailable.</p><pre>" +
      escape(summary(id)) +
      "</pre></body></html>"
    );
  }

  public List<Map<String, Object>> artifacts(final String id) {
    final var run = store.run(id);
    if (!run.state().terminal()) return List.of();
    final String json = summary(id),
      html = html(id);
    artifacts.write(id, "summary.json", json);
    artifacts.write(id, "report.html", html);
    return List.of(metadata(id, "summary.json", json), metadata(id, "report.html", html));
  }

  private Map<String, Object> metadata(final String id, final String name, final String content) {
    return Map.of(
      "name",
      name,
      "sha256",
      Json.hash(content),
      "size",
      content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
      "url",
      "/api/v1/runs/" + id + "/artifacts/" + name
    );
  }

  public String download(final String id, final String name) {
    if (!Set.of("summary.json", "report.html").contains(name)) throw Problem.missing("Artifact");
    if (artifacts(id).isEmpty()) throw Problem.conflict(
      "Artifacts are available after the run completes"
    );
    return artifacts.read(id, name);
  }

  private static String escape(final String text) {
    return text
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#39;");
  }
}

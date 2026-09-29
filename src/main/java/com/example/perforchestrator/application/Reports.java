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

  public Reports(Store store, Ports.ArtifactStore artifacts) {
    this.store = store;
    this.artifacts = artifacts;
  }

  public String summary(String id) {
    var run = store.run(id);
    var plan = store.plan(run.planId());
    return Json.write(
        Map.of(
            "simulated",
            plan.simulated(),
            "run",
            run,
            "plan",
            plan,
            "events",
            store.events(id, 0),
            "metricUnits",
            Map.of(
                "latency_p95_ms",
                plan.simulated() ? "milliseconds (synthetic aggregate p95)" : "milliseconds (configured query must return these units)",
                "error_rate",
                "failed requests / measured requests",
                "throughput_rps",
                "requests/second",
                "request_count",
                "requests"),
            "provenance",
            plan.simulated() ? "Deterministic simulation fixtures; no actual requests or resource telemetry" : "Real Helm execution and configured LogQL queries; missing measurements remain unavailable",
            "cleanupPolicy",
            plan.simulated() ? "KEEP services; stop owned simulated load" : "KEEP services; uninstall owned load-generator release"));
  }

  public String html(String id) {
    var run = store.run(id);
    var plan = store.plan(run.planId());
    if (!plan.simulated()) return "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>Real performance report</title><body><h1>Real performance report</h1><p>Missing measurements are unavailable, never synthetic.</p><pre>" + escape(summary(id)) + "</pre></body></html>";
    StringBuilder html =
        new StringBuilder(
            "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><meta name=\"viewport\""
                + " content=\"width=device-width\"><title>Simulation report</title><link"
                + " rel=\"stylesheet\" href=\"/css/app.css\"><body><main class=\"report\">");
    html.append("<p class=\"eyebrow\">PERFORMANCE ORCHESTRATOR · SIMULATION</p><h1>")
        .append(escape(plan.profile().name()))
        .append("</h1>");
    html.append("<p>")
        .append(escape(run.state() + " · performance " + run.verdict()))
        .append(
            "</p><div class=\"banner\">Synthetic evidence only. No real requests were sent. Missing"
                + " telemetry is unavailable, never zero.</div>");
    html.append("<section class=\"card\"><h2>Execution summary</h2><table><tbody>");
    row(html, "Run ID", run.id());
    row(
        html,
        "Profile revision",
        plan.profileId() == null
            ? "Inline profile snapshot"
            : String.valueOf(plan.profileRevision()));
    row(html, "Environment", run.environment());
    row(html, "Started (UTC)", run.startedAt());
    row(html, "Updated (UTC)", run.updatedAt());
    row(html, "Measurement start (UTC)", run.measurementStartedAt());
    row(html, "Measurement end (UTC)", run.measurementEndedAt());
    row(
        html,
        "Applied load",
        plan.profile().loadGenerator().virtualUsers()
            + " users / "
            + plan.profile().loadGenerator().requestsPerSecond()
            + " requests per second");
    row(html, "Outcome", run.message());
    row(html, "Cleanup", run.cleanupOutcome());
    html.append("</tbody></table></section>");
    html.append(
        "<section class=\"card spacer\"><h2>Measurements &"
            + " thresholds</h2><table><thead><tr><th>METRIC</th><th>VALUE</th><th>MAXIMUM</th><th>RESULT</th></tr></thead><tbody>");
    for (var threshold : plan.profile().thresholds()) {
      Double value = run.metrics().get(threshold.metric());
      html.append("<tr><td>")
          .append(escape(threshold.metric()))
          .append("</td><td>")
          .append(value == null ? "Unavailable" : value)
          .append("</td><td>")
          .append(threshold.maximum())
          .append("</td><td>")
          .append(value == null ? "INCONCLUSIVE" : value <= threshold.maximum() ? "PASS" : "FAIL")
          .append("</td></tr>");
    }
    html.append(
        "</tbody></table><p class=\"muted\">latency_p95_ms: milliseconds; error_rate: failed /"
            + " measured requests; throughput_rps: requests/second; request_count: requests. Warmup"
            + " is excluded. All values are synthetic.</p>");
    html.append("<pre>")
        .append(escape(Json.write(run.metrics())))
        .append(
            "</pre></section><section class=\"card spacer\"><h2>Selected service"
                + " versions</h2><table><thead><tr><th>SERVICE</th><th>SOURCE /"
                + " VERSION</th><th>NAMESPACE</th></tr></thead><tbody>");
    for (var service : plan.services())
      html.append("<tr><td>")
          .append(escape(service.serviceId()))
          .append("</td><td>")
          .append(escape(service.image().sourceRef() + " / " + service.image().version()))
          .append("</td><td>")
          .append(escape(service.namespace()))
          .append("</td></tr>");
    html.append(
        "</tbody></table></section><section class=\"card spacer\"><h2>Timeline</h2><ol"
            + " class=\"timeline\">");
    for (var event : store.events(id, 0))
      html.append("<li><time>")
          .append(escape(event.time()))
          .append("</time><strong>")
          .append(escape(event.state()))
          .append("</strong><p>")
          .append(escape(event.message()))
          .append("</p></li>");
    html.append("</ol></section><details><summary>Complete pinned execution record</summary><pre>")
        .append(escape(summary(id)))
        .append("</pre></details></main></body></html>");
    return html.toString();
  }

  private static void row(StringBuilder html, String label, String value) {
    html.append("<tr><th>")
        .append(escape(label))
        .append("</th><td>")
        .append(escape(value == null ? "Unavailable" : value))
        .append("</td></tr>");
  }

  public List<Map<String, Object>> artifacts(String id) {
    var run = store.run(id);
    if (!run.state().terminal()) return List.of();
    String json = summary(id), html = html(id);
    artifacts.write(id, "summary.json", json);
    artifacts.write(id, "report.html", html);
    return List.of(metadata(id, "summary.json", json), metadata(id, "report.html", html));
  }

  private Map<String, Object> metadata(String id, String name, String content) {
    return Map.of(
        "name",
        name,
        "sha256",
        Json.hash(content),
        "size",
        content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
        "url",
        "/api/v1/runs/" + id + "/artifacts/" + name);
  }

  public String download(String id, String name) {
    if (!Set.of("summary.json", "report.html").contains(name)) throw Problem.missing("Artifact");
    if (artifacts(id).isEmpty())
      throw Problem.conflict("Artifacts are available after the run completes");
    return artifacts.read(id, name);
  }

  private static String escape(String text) {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }
}

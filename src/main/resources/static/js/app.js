import { runMonitoring } from "./run-monitoring.js";
import { realFlow } from "./real-flow.js";
import { el, labeled, input, select } from "./dom.js";
import { secretSignInPrompt } from "./secret-sign-in.js";
import { operationAuthentication } from "./operation-auth.js";
import { connectionDiagnostics } from "./connection-diagnostics.js";
import { configurationManager } from "./configuration-manager.js";
import { deploymentEditor } from "./deployments.js";
const app = document.querySelector("#app");
const terminal = new Set([
  "SUCCEEDED",
  "FAILED",
  "CANCELLED",
  "NEEDS_ATTENTION",
]);
let vaultPrompt;
let session,
  catalog,
  profiles = [],
  poll,
  routeGeneration = 0;
let pageCleanup = () => {};
const $ = (selector, root = document) => root.querySelector(selector);
const button = (text, fn, style = "") =>
  el("button", { type: "button", class: style, onclick: () => busy(fn) }, text);
const link = (text, href, style = "") => el("a", { href, class: style }, text);
const pill = (text) =>
  el(
    "span",
    {
      class: `pill ${["FAILED", "NEEDS_ATTENTION", "FAIL"].includes(text) ? "bad" : ""}`,
    },
    text.replaceAll("_", " "),
  );
const pretty = (value) => JSON.stringify(value, null, 2);
const time = (value) =>
  value ? new Date(value).toLocaleString() : "Unavailable";
function toast(message) {
  $("#notice").textContent = message;
  setTimeout(() => {
    $("#notice").textContent = "";
  }, 5000);
}
async function api(path, options = {}) {
  const response = await fetch("/api/v1" + path, {
    ...options,
    headers: {
      Accept: "application/json",
      ...(options.body ? { "Content-Type": "application/json" } : {}),
      ...(session ? { [session.csrfHeader]: session.csrfToken } : {}),
      ...options.headers,
    },
    body: options.body ? JSON.stringify(options.body) : undefined,
  });
  if (!response.ok) {
    let error;
    try {
      error = await response.json();
    } catch {
      error = { message: `Request failed (${response.status})` };
    }
    throw new Error(
      (error.field ? error.field + ": " : "") +
        (error.message || `Request failed (${response.status})`),
    );
  }
  return response.json();
}
async function busy(fn) {
  try {
    await fn();
  } catch (error) {
    showError(error.message);
  }
}
function showError(message) {
  let node = $("#error", app);
  if (!node) {
    node = el("div", { id: "error", class: "error", role: "alert" });
    app.prepend(node);
  }
  node.textContent = message;
  node.scrollIntoView({ block: "nearest" });
}
function heading(eyebrow, title, subtitle, action) {
  return el(
    "div",
    { class: "heading" },
    el(
      "div",
      {},
      el("p", { class: "eyebrow" }, eyebrow),
      el("h1", {}, title),
      el("p", { class: "muted" }, subtitle),
    ),
    action,
  );
}
function detail(title, value) {
  return el(
    "details",
    {},
    el("summary", {}, title),
    el("pre", {}, typeof value === "string" ? value : pretty(value)),
  );
}
function stat(title, value, foot) {
  return el(
    "div",
    { class: "stat" },
    el("span", {}, title),
    el("strong", {}, value),
    el("small", {}, foot),
  );
}
async function loadCatalog() {
  const [env, services, sources, scenarios] = await Promise.all(
    ["/environments", "/services", "/image-sources", "/scenarios"].map((p) =>
      api(p),
    ),
  );
  catalog = { env, services, sources, scenarios };
  profiles = await api("/profiles");
}
function runTable(runs) {
  if (!runs.length)
    return el(
      "div",
      { class: "card empty" },
      "No runs yet. Start with a saved profile or configure a new run.",
    );
  return el(
    "div",
    { class: "table-wrap" },
    el(
      "table",
      {},
      el(
        "thead",
        {},
        el(
          "tr",
          {},
          ["RUN", "ENVIRONMENT", "STATUS", "PERFORMANCE", "STARTED"].map((t) =>
            el("th", {}, t),
          ),
        ),
      ),
      el(
        "tbody",
        {},
        runs.map((r) =>
          el(
            "tr",
            {},
            el("td", {}, link(r.id.slice(0, 8), "#run/" + r.id)),
            el("td", {}, r.environment),
            el("td", {}, pill(r.state)),
            el("td", {}, r.verdict.replaceAll("_", " ")),
            el("td", {}, time(r.createdAt)),
          ),
        ),
      ),
    ),
  );
}
async function launch(body) {
  const run = await api("/runs", {
    method: "POST",
    body,
    headers: { "Idempotency-Key": crypto.randomUUID() },
  });
  location.hash = "#run/" + run.id;
}
async function dashboard() {
  const runs = await api("/runs");
  const active = runs.filter((r) => !terminal.has(r.state)).length;
  app.append(
    heading(
      "WORKSPACE OVERVIEW",
      "Performance, with a plan.",
      "Prepare environments. Run repeatable tests. Keep the evidence.",
      link("+ Configure a run", "#configure", "button primary"),
    ),
    el(
      "div",
      { class: "banner" },
      "Simulation workspace — deployments and performance measurements are synthetic. Your clusters and source projects remain untouched.",
    ),
    el(
      "div",
      { class: "stats" },
      stat(
        "Active runs",
        active,
        "Persistent execution, independent of your browser",
      ),
      stat(
        "Available environments",
        Object.values(catalog.env).filter((e) => e.available).length,
        "One mutating run per environment",
      ),
      stat(
        "Saved profiles",
        profiles.length,
        "Repeatable configurations, ready to run",
      ),
    ),
  );
  app.append(
    el(
      "div",
      { class: "section-heading" },
      el("h2", {}, "Saved profiles"),
      link("Create profile", "#configure"),
    ),
  );
  app.append(
    el(
      "div",
      { class: "grid" },
      profiles.map((saved) =>
        el(
          "article",
          { class: "card" },
          el(
            "div",
            { class: "card-head" },
            el(
              "div",
              {},
              el("h3", {}, saved.profile.name),
              el(
                "span",
                { class: "subtle" },
                `${saved.profile.targetEnvironment} · revision ${saved.revision}`,
              ),
            ),
            pill("SAVED"),
          ),
          el(
            "p",
            { class: "muted" },
            `${saved.profile.loadGenerator.virtualUsers} virtual users · ${saved.profile.loadGenerator.measurementSeconds}s measurement · ${saved.profile.simulationCase.toLowerCase().replaceAll("_", " ")}`,
          ),
          el(
            "div",
            { class: "service-chips" },
            saved.profile.services.map((s) =>
              el(
                "span",
                { class: "chip" },
                `${s.serviceId} / ${s.build.sourceRef}`,
              ),
            ),
          ),
          el(
            "div",
            { class: "card-actions" },
            button(
              "Run now",
              () => launch({ profileId: saved.id, revision: saved.revision }),
              "primary",
            ),
            link("Edit", "#configure/" + saved.id, "button"),
          ),
        ),
      ),
    ),
  );
  app.append(
    el(
      "div",
      { class: "section-heading" },
      el("h2", {}, "Recent runs"),
      link("View history", "#history"),
    ),
    runTable(runs.slice(0, 6)),
    el(
      "div",
      { class: "section-heading" },
      el("h2", {}, "Environment availability"),
    ),
    el(
      "div",
      { class: "environment-list" },
      Object.entries(catalog.env).map(([id, value]) =>
        el(
          "div",
          { class: "environment" },
          el("span", { class: "dot" }),
          id,
          el("small", {}, value.available ? "Available" : "Reserved"),
        ),
      ),
    ),
  );
}
function defaultProfile() {
  return {
    name: "",
    targetEnvironment: "sandbox",
    services: [],
    loadGenerator: {
      templateRef: "smoke",
      virtualUsers: 10,
      requestsPerSecond: 100,
      warmupSeconds: 1,
      measurementSeconds: 5,
      configurationOverlay: "",
    },
    maxRunDurationSeconds: 60,
    thresholds: [
      { metric: "latency_p95_ms", maximum: 500, required: true },
      { metric: "error_rate", maximum: 0.01, required: true },
    ],
    simulationCase: "SUCCESS",
  };
}
async function configure(id) {
  let saved = id ? await api("/profiles/" + id) : null,
    p = saved?.profile || defaultProfile();
  app.append(
    heading(
      "CONFIGURATION",
      saved ? "Edit performance profile" : "Configure run",
      "Choose each service’s source and version independently.",
    ),
  );
  const form = el("form");
  form.addEventListener("submit", (e) => e.preventDefault());
  const name = input(p.name),
    environment = select(
      Object.keys(catalog.env).map((e) => [e, e]),
      p.targetEnvironment,
    );
  const top = el(
    "section",
    { class: "card" },
    el("h2", {}, "01  Profile & target"),
    el(
      "div",
      { class: "form-grid spacer" },
      labeled("Profile name", name),
      labeled("Target environment", environment),
    ),
  );
  form.append(top);
  const targetHint = el("div", { class: "target-hint" });
  const deployments = deploymentEditor({
    catalog,
    api,
    environment: environment.value,
    selections: p.services,
  });
  const describeEnvironment = () => {
    const config = catalog.env[environment.value].configuration;
    targetHint.replaceChildren(
      el(
        "p",
        {},
        "The target environment selects the cluster and each service’s namespace, release name and values files. It does not select an image source.",
      ),
      el(
        "div",
        { class: "service-chips" },
        el("span", { class: "chip" }, `Cluster: ${config.clusterIdentity}`),
        el(
          "span",
          { class: "chip" },
          `Load generator: ${config.loadGeneratorNamespace}`,
        ),
      ),
    );
  };
  environment.addEventListener("change", () => {
    deployments.setEnvironment(environment.value);
    describeEnvironment();
  });
  describeEnvironment();
  top.append(targetHint);
  form.append(deployments.element);
  pageCleanup = () => deployments.dispose();
  const load = p.loadGenerator;
  const scenario = select(
      Object.entries(catalog.scenarios).map(([id, s]) => [id, s.displayName]),
      load.templateRef,
    ),
    users = input(load.virtualUsers, "number", { min: 1 }),
    rps = input(load.requestsPerSecond, "number", { min: 1 }),
    warmup = input(load.warmupSeconds, "number", { min: 0 }),
    measurement = input(load.measurementSeconds, "number", { min: 1 }),
    max = input(p.maxRunDurationSeconds, "number", { min: 15 }),
    caseSelect = select(
      [
        "SUCCESS",
        "DEPLOYMENT_FAILURE",
        "READINESS_TIMEOUT",
        "MISSING_METRICS",
        "THRESHOLD_FAILURE",
        "CLEANUP_FAILURE",
      ].map((v) => [v, v.toLowerCase().replaceAll("_", " ")]),
      p.simulationCase,
    ),
    loadOverlay = el("textarea", {}, load.configurationOverlay || "");
  const thresholds = el("textarea", {}, pretty(p.thresholds));
  form.append(
    el(
      "section",
      { class: "card spacer" },
      el("h2", {}, "03  Load & evaluation"),
      el(
        "div",
        { class: "form-grid spacer" },
        labeled("Scenario", scenario),
        labeled("Simulation behavior", caseSelect),
        labeled("Virtual users", users),
        labeled("Requests per second", rps),
        labeled("Warmup (seconds)", warmup),
        labeled("Measurement (seconds)", measurement),
        labeled("Maximum run duration (seconds)", max),
        labeled("Scenario YAML overlay", loadOverlay),
        labeled("Thresholds (JSON; maximum is inclusive)", thresholds),
      ),
    ),
  );
  const read = () => ({
    name: name.value,
    targetEnvironment: environment.value,
    services: deployments.selections(),
    loadGenerator: {
      templateRef: scenario.value,
      virtualUsers: Number(users.value),
      requestsPerSecond: Number(rps.value),
      warmupSeconds: Number(warmup.value),
      measurementSeconds: Number(measurement.value),
      configurationOverlay: loadOverlay.value,
    },
    maxRunDurationSeconds: Number(max.value),
    simulationCase: caseSelect.value,
    thresholds: JSON.parse(thresholds.value),
  });
  form.append(
    el(
      "div",
      { class: "form-footer" },
      button(
        "Save profile",
        async () => {
          saved = await api(saved ? "/profiles/" + saved.id : "/profiles", {
            method: saved ? "PUT" : "POST",
            body: { revision: saved?.revision, profile: read() },
          });
          toast("Profile saved · revision " + saved.revision);
        },
        "primary",
      ),
      button("Preview execution plan", async () => {
        const plan = await api("/plans", {
          method: "POST",
          body: { profile: read() },
        });
        location.hash = "#plan/" + plan.id;
      }),
    ),
  );
  app.append(form);
}
async function planScreen(id) {
  const plan = await api("/plans/" + id);
  app.append(
    heading(
      "IMMUTABLE EXECUTION PLAN",
      plan.profile.name,
      `${plan.profile.targetEnvironment} · ${plan.services.length} services · expires ${time(plan.expiresAt)}`,
      button("Run this plan", () => launch({ planId: id }), "primary"),
    ),
    el(
      "div",
      { class: "banner" },
      plan.warnings.map((w) => el("div", {}, w)),
    ),
    el(
      "div",
      { class: "grid" },
      plan.services.map((s) =>
        el(
          "section",
          { class: "card" },
          el(
            "div",
            { class: "card-head" },
            el("h2", {}, s.serviceId),
            pill(s.action),
          ),
          el(
            "p",
            { class: "muted" },
            `${s.image.sourceRef} / ${s.image.version} → ${s.namespace}`,
          ),
          detail("Before / after configuration", s.changes),
          detail("Pinned image & prepared inputs", s),
        ),
      ),
    ),
    el(
      "section",
      { class: "card spacer" },
      el("h2", {}, "Load settings & policy"),
      el(
        "p",
        { class: "muted" },
        "Service versions remain deployed. Only owned simulated load resources are stopped.",
      ),
      detail("Load configuration", plan.profile.loadGenerator),
      detail("Plan checksum", plan.checksum),
    ),
  );
}
async function history() {
  app.append(
    heading(
      "EXECUTION HISTORY",
      "Every run, recorded.",
      "Inspect outcomes, compare inputs, and download execution evidence.",
    ),
  );
  const env = select(
      [
        ["", "All environments"],
        ...Object.keys(catalog.env).map((x) => [x, x]),
      ],
      "",
    ),
    state = select(
      [
        ["", "All outcomes"],
        ...["SUCCEEDED", "FAILED", "CANCELLED", "NEEDS_ATTENTION"].map((x) => [
          x,
          x,
        ]),
      ],
      "",
    ),
    version = input("");
  const results = el("div");
  const refresh = async () => {
    const params = new URLSearchParams();
    if (env.value) params.set("environment", env.value);
    if (state.value) params.set("outcome", state.value);
    if (version.value) params.set("version", version.value);
    results.replaceChildren(runTable(await api("/runs?" + params)));
  };
  app.append(
    el(
      "div",
      { class: "toolbar" },
      labeled("Environment", env),
      labeled("Outcome", state),
      labeled("Exact service version", version),
      button("Filter", refresh),
    ),
    results,
  );
  await refresh();
}
async function runDetails(id, generation) {
  const initial = await api("/runs/" + id),
    plan = await api("/plans/" + initial.planId);
  const status = el("div"),
    timeline = el("ol", { class: "timeline" }),
    metrics = el("div", { class: "metrics" }),
    actions = el("div", { class: "card-actions" }),
    monitoring = el("p", { class: "muted" });
  app.append(
    heading(
      "RUN DETAILS",
      plan.profile.name,
      `${plan.profile.targetEnvironment} · ${id} · Prepared by ${plan.actor}`,
    ),
    status,
    plan.simulated ? null : link("Open monitoring", "#monitor/" + id, "button primary"),
    el(
      "div",
      { class: "detail-grid spacer" },
      el(
        "section",
        { class: "card" },
        el("h2", {}, "Execution timeline"),
        timeline,
      ),
      el(
        "div",
        {},
        el(
          "section",
          { class: "card" },
          el("h2", {}, "Performance measurements"),
          monitoring,
          metrics,
          actions,
        ),
        el(
          "section",
          { class: "card spacer" },
          el("h2", {}, "Pinned inputs"),
          el("p", { class: "muted" }, "Inputs are frozen for this execution."),
          link("Inspect original plan", "#plan/" + plan.id),
          detail(
            "Selected services",
            plan.services.map((s) => ({
              service: s.serviceId,
              source: s.image.sourceRef,
              version: s.image.version,
              namespace: s.namespace,
            })),
          ),
          plan.simulated ? button("Rerun original pinned plan", () => launch({ planId: plan.id })) : link("Prepare a new real run", "#configure", "button"),
          plan.profileId
            ? button("Run current saved profile", () =>
                launch({ profileId: plan.profileId }),
              )
            : null,
        ),
      ),
    ),
  );
  let cursor = 0;
  const update = async () => {
    const [run, events] = await Promise.all([
      api("/runs/" + id),
      api(`/runs/${id}/events?after=${cursor}`),
    ]);
    if (generation !== routeGeneration) return;
    status.replaceChildren(
      el(
        "div",
        { class: "inline" },
        pill(run.state),
        pill(run.verdict),
        el("span", { class: "muted" }, run.message),
      ),
    );
    for (const event of events) {
      timeline.append(
        el(
          "li",
          {},
          el("time", {}, time(event.time)),
          el("strong", {}, event.state.replaceAll("_", " ")),
          el("p", {}, event.message),
        ),
      );
      cursor = event.id;
    }
    const units = {
      request_count: "requests",
      throughput_rps: "requests / second",
      error_rate: "error ratio",
      latency_p95_ms: "p95 latency / ms",
    };
    metrics.replaceChildren(
      ...Object.entries(units).map(([key, label]) =>
        el(
          "div",
          { class: "metric" },
          el("strong", {}, run.metrics[key] ?? "—"),
          el("span", {}, label),
        ),
      ),
    );
    monitoring.textContent = plan.simulated ? `Synthetic data · updated ${time(run.updatedAt)}. CPU, memory and logs unavailable.` : `Real LogQL measurements · updated ${time(run.updatedAt)}. Missing measurements are unavailable, not zero.`;
    actions.replaceChildren();
    if (!terminal.has(run.state))
      actions.append(
        button(
          "Cancel run",
          () => api("/runs/" + id + "/cancel", { method: "POST" }),
          "danger",
        ),
      );
    else {
      actions.append(
        link("View report", `/api/v1/runs/${id}/report`, "button"),
        link(
          "JSON summary",
          `/api/v1/runs/${id}/artifacts/summary.json`,
          "button",
        ),
      );
      if (run.state === "NEEDS_ATTENTION")
        actions.append(
          button("Verify cleanup & release", async () => {
            await api((plan.simulated ? "/runs/" : "/real/runs/") + id + "/recover", { method: "POST" });
            await update();
          }),
        );
    }
    const dashboard = catalog.env[run.environment]?.configuration.dashboardUrl;
    if (dashboard && /^https:\/\//.test(dashboard))
      actions.append(link("Open Grafana", dashboard, "button"));
    if (!terminal.has(run.state)) poll = setTimeout(refresh, 1500);
  };
  const refresh = async () => {
    try {
      await update();
    } catch (error) {
      if (generation === routeGeneration) {
        showError(error.message);
        poll = setTimeout(refresh, 3000);
      }
    }
  };
  await refresh();
}
async function configurationEditor() {
  let document = await api("/configuration");
  const startup = await api("/configuration/startup");
  const sections = [
    ["all", "Complete configuration"],
    ["catalog.environments", session.mode === "real" ? "Environments & monitoring" : "Environments & simulation limits"],
    ["catalog.services", "Services, namespaces & releases"],
    ["catalog.imageSources", "Mock image sources & versions"],
    ["catalog.scenarios", "Load scenario templates"],
    ["connections.artifactory", "Artifactory connections"],
    ["connections.bitbucket", "Bitbucket connections"],
    ["connections.loki", "Shared Loki connections"],
    ["connections.secretServers", "Secret Server authentication"],
    ["connections.credentials", "Credential references & secret IDs"],
    ["connections.imageSources", "Real image-source mappings"],
  ].filter(([key]) => session.mode === "simulation" || key !== "catalog.imageSources");
  const selector = select(sections, sections[0][0]);
  const editor = el("textarea", { rows: 20, spellcheck: "false", "aria-label": "Configuration JSON" });
  let selected = selector.value;
  const value = () => { if (selected === "all") return document; const [group, section] = selected.split("."); return document[group][section]; };
  const capture = () => {
    if (selected === "all") {
      const parsed = JSON.parse(editor.value);
      if (!parsed || !parsed.catalog || !parsed.connections) throw new Error("Complete configuration requires catalog and connections.");
      document = { ...parsed, revision: document.revision };
      return;
    }
    const [group, section] = selected.split(".");
    document[group][section] = JSON.parse(editor.value);
  };
  editor.value = pretty(value());
  selector.addEventListener("change", () => {
    try { capture(); selected = selector.value; editor.value = pretty(value()); }
    catch { selector.value = selected; showError("Fix the JSON in this section before switching sections."); }
  });
  const status = el("p", { class: "muted", role: "status" }, "Changes apply when saved. Enter secret references only, never passwords or tokens.");
  const save = button("Save configuration", async () => {
    save.disabled = true;
    try {
      capture();
      document = await api("/configuration", { method: "PUT", body: document });
      await route();
      toast("Configuration saved. New requests use the updated settings.");
    } finally { save.disabled = false; }
  }, "primary");
  const reload = button("Discard edits & reload", async () => {
    document = await api("/configuration");
    editor.value = pretty(value());
    status.textContent = "Loaded the current saved configuration.";
  });
  const file = el("input", { type: "file", hidden: true, accept: ".json,application/json", "aria-label": "Import configuration JSON file" });
  const fileName = el("span", { class: "muted", role: "status" }, "JSON file · maximum 256 KiB");
  const importButton = el("button", { type: "button", onclick: () => file.click() }, "Import JSON…");
  file.addEventListener("change", async () => {
    const chosen = file.files[0];
    if (!chosen) return;
    try {
      if (chosen.size > 262144) throw new Error("Configuration must be at most 256 KiB.");
      const imported = JSON.parse(await chosen.text());
      if (!imported || typeof imported !== "object" || Array.isArray(imported)
          || Object.keys(imported).some(key => !["revision", "catalog", "connections"].includes(key))
          || !imported.catalog || !imported.connections)
        throw new Error("Import a configuration export containing catalog and connections.");
      imported.connections.loki ||= {};
      imported.connections.bitbucket ||= {}; // Older exports did not include Bitbucket.
      for (const [key] of sections.filter(([key]) => key !== "all")) {
        const [group, section] = key.split(".");
        const value = imported[group][section];
        if (!value || typeof value !== "object" || Array.isArray(value))
          throw new Error(`Missing configuration map: ${key}`);
      }
      if (imported.catalog.mode !== session.mode)
        throw new Error("Imported configuration must match the running mode.");
      if (startup.environment && (Object.keys(imported.catalog.environments).length !== 1
          || !imported.catalog.environments[startup.environment]
          || imported.catalog.environments[startup.environment].clusterIdentity !== startup.configuration.catalog.environments[startup.environment].clusterIdentity))
        throw new Error(`Import must match this instance's environment and cluster: ${startup.environment}`);
      document = { ...imported, revision: document.revision };
      selected = "all";
      selector.value = "all";
      fileName.textContent = chosen.name;
      editor.value = pretty(value());
      status.textContent = "Imported into the editor only. Review all sections, then Save configuration to validate and apply. This replaces the complete runtime configuration.";
    } catch (error) { showError(error.message); }
    finally { file.value = ""; }
  });
  const exportSaved = button("Export active configuration", async () => {
    const active = await api("/configuration");
    const url = URL.createObjectURL(new Blob([pretty(active)], { type: "application/json" }));
    const link = el("a", { href: url, download: `orchestrator-${session.mode}-configuration.json` });
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    status.textContent = "Exported active configuration. Unsaved editor changes are not included. Review internal URLs and any values you entered before sharing.";
  });
  const restore = button("Load startup defaults into editor", async () => {
    const baseline = await api("/configuration/startup");
    document = { ...baseline.configuration, revision: document.revision };
    editor.value = pretty(value());
    status.textContent = "Loaded startup configuration into the editor. Review and save to replace runtime settings. Saving clears dashboard overrides so future startup defaults apply. The runtime file remains as an empty override document.";
  });
  return el("details", { class: "card spacer" }, el("summary", {}, "Advanced JSON · import, export & restore"),
    el("p", { class: "muted" }, startup.runtimeOverride
      ? "Active source: startup settings plus saved dashboard changes. Only changed fields override startup defaults."
      : "Active source: startup YAML / Helm settings. No saved runtime override is loaded."),
    el("p", { class: "muted" }, "Saved settings survive restarts. Active runs retain their prepared inputs; older unsubmitted plans must be prepared again after catalog changes. Connection changes require a fresh Secret Server sign-in."),
    startup.environment ? el("p", { class: "muted" }, `Instance environment: ${startup.environment}. Environment and cluster are fixed at startup; imports cannot switch them.`) : null,
    labeled("Configuration section", selector), labeled("JSON", editor), status,
    el("div", { class: "card-actions" }, save, reload, exportSaved, restore),
    el("div", { class: "card-actions config-import" }, importButton, fileName, file));
}

async function settings(section) {
  const active = await api("/configuration");
  const startup = await api("/configuration/startup");
  const authStates = await api("/secret-auth");
  app.append(heading("WORKSPACE SETTINGS", "Connections & catalog", "Manage connections and service settings. Changes apply when saved."),
    el("p", { class: "banner" }, session.mode === "real"
      ? "Real mode · Vault authentication, image discovery and Bitbucket references are available. Real execution requires startup cluster configuration; LogQL measurements require approved queries."
      : "Simulation mode · Deployments and results are synthetic. Registry diagnostics use configured real connections."),
    el("p", { class: "muted" }, startup.runtimeOverride
      ? "Startup defaults + saved dashboard changes. Export your configuration below to keep a backup."
      : "Showing startup defaults from application.yaml and local / Helm overrides."),
    configurationManager(active, { api, mode: session.mode, authStates,
      onSaved: async () => { await route(); toast("Settings updated."); },
      onSignIn: id => busy(() => vaultPrompt.open(id)),
      onConnectionSession: (kind, id) => {
        const auth = operationAuthentication(active.connections[kind][id], { api, kind, id });
        const dialog = el("dialog", { class: "deployment-dialog auth-dialog", "aria-label": "Connection session" },
          el("h2", {}, `Connection: ${id}`), auth.node,
          el("div", { class: "dialog-actions" }, el("button", { type: "button", onclick: () => dialog.close() }, "Close")));
        dialog.addEventListener("close", () => { auth.clear(); dialog.remove(); });
        document.body.append(dialog); dialog.showModal();
      } }));
  app.append(await configurationEditor());
  const diagnostics = el("details", { class: "card spacer" }, el("summary", {}, "Connection diagnostics"),
    el("p", { class: "muted" }, "Test registry tags and Bitbucket references with configured connections. These requests do not deploy anything."));
  app.append(diagnostics);
  const config = await api("/connections");
  const panel = connectionDiagnostics(active.catalog, config, api);
  diagnostics.append(panel.node);
  if (section === "diagnostics") { diagnostics.open = true; diagnostics.scrollIntoView({ block: "start" }); }
  pageCleanup = () => panel.dispose();

}
function realOverview() {
  const enabled = session.capabilities.execution;
  app.append(heading("REAL INTEGRATIONS", "Performance workspace", "Repository-backed Helm deployments and read-only service diagnostics."),
    el("section", { class: "card" }, el("h2", {}, enabled ? "Run a real performance test" : "Real execution needs cluster configuration"),
      el("p", {}, enabled
        ? "Prepare service charts and a load profile from CKP directories, review pinned inputs, then deploy and run. Owned load is uninstalled at completion; services remain."
        : "Configure orchestrator.execution.enabled, kube-context and expected-api-server, then restart. Git, Helm and kubectl must be installed on the application host."),
      el("div", { class: "card-actions" },
        enabled ? link("Configure run", "#configure", "button primary") : null,
        link("Browse service diagnostics", "#settings/diagnostics", "button"),
        link("Configure connections", "#settings", "button"))),
    el("section", { class: "card spacer" }, el("h2", {}, "Measurement requirements"),
      el("p", {}, "Load rates and destinations come from selected chart values. Supply organization-approved LogQL queries to measure actual traffic. Without measurements and thresholds, the performance verdict is inconclusive."),
      link("View run history", "#history", "button")));
}

async function route() {
  pageCleanup();
  pageCleanup = () => {};
  clearTimeout(poll);
  const generation = ++routeGeneration;
  app.replaceChildren(el("p", { class: "muted" }, "Loading workspace…"));
  const [page = "dashboard", id] = (
    location.hash.slice(1) || "dashboard"
  ).split("/");
  document
    .querySelectorAll("nav a")
    .forEach((a) => a.classList.toggle("active", a.hash === "#" + page));
  try {
    await loadCatalog();
    if (generation !== routeGeneration) return;
    app.replaceChildren();
    if (session.mode === "real" && page === "configure") {
      app.append(heading("REAL EXECUTION", "Configure run", "Prepare and review the deployment before starting."));
      const flow = await realFlow(api, { services: catalog.services, environment: session.targetEnvironment }, hash => { location.hash = hash; });
      if (generation !== routeGeneration) { flow.dispose?.(); return; }
      app.append(flow);
      pageCleanup = () => flow.dispose?.();
    }
    else if (session.mode === "real" && page === "plan") {
      const prepared = await api("/plans/" + id);
      app.append(heading("PREPARED REAL PLAN", prepared.profile.name, prepared.clusterIdentity),
        el("ul", {}, prepared.warnings.map(warning => el("li", {}, warning))),
        el("pre", {}, pretty(prepared.services.map(s => ({ service: s.serviceId, namespace: s.namespace, release: s.releaseName, commit: s.sourceRevision, image: s.image, effectiveValues: s.effectiveValues })))),
        link("Prepare another run", "#configure", "button"));
    }
    else if (session.mode === "real" && !["settings", "history", "run", "monitor"].includes(page)) realOverview();
    else if (page === "configure") await configure(id);
    else if (page === "plan") await planScreen(id);
    else if (page === "history") await history();
    else if (page === "monitor") {
      const view = await runMonitoring(api, id, {services: catalog.services, environment: session.targetEnvironment});
      if (generation !== routeGeneration) {view.dispose();return;}
      app.append(view);pageCleanup=()=>view.dispose();
    }
    else if (page === "run") await runDetails(id, generation);
    else if (page === "settings") await settings(id);
    else await dashboard();
    if (session.mode === "real") await vaultPrompt?.refresh();
  } catch (error) {
    showError(error.message);
  }
}
session = await api("/session");
vaultPrompt = secretSignInPrompt(api, async state => {
  toast(state.state === "AUTHENTICATED" ? `Signed in as ${state.username} until ${time(state.expiresAt)}.` : `Vault token stored until ${time(state.expiresAt)}; access is verified when a secret is requested.`);
  await route();
}, session.mode === "real");
$("#mode-badge").textContent = session.mode === "real" ? (session.capabilities.execution ? "REAL · EXECUTION" : "REAL · READ-ONLY") : "SIMULATION";
$("#workspace-mode").textContent = session.mode === "real" ? `Environment: ${session.targetEnvironment || "unbound"} · Real integrations` : "Simulation mode · synthetic deployments and results";
if (session.mode === "real") document.querySelector('nav a[href="#configure"]').hidden = !session.capabilities.execution;
window.addEventListener("hashchange", route);
await route();

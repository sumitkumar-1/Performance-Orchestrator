import { pinnedInputs } from "./pinned-inputs.js";
import { runMonitoring } from "./run-monitoring.js";
import { additionalLoads } from "./additional-loads.js";
import { runBuilder } from "./run-builder.js";
import { diagnosticsPanel } from "./diagnostics.js";
import { el, labeled, input, select } from "./dom.js";
import { secretSignInPrompt } from "./secret-sign-in.js";
import { operationAuthentication } from "./operation-auth.js";
import { connectionDiagnostics } from "./connection-diagnostics.js";
import { configurationManager } from "./configuration-manager.js";
const app = document.querySelector("#app");
const terminal = new Set(["SUCCEEDED", "FAILED", "CANCELLED", "NEEDS_ATTENTION"]);
let vaultPrompt;
let session,
  catalog,
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
const time = (value) => (value ? new Date(value).toLocaleString() : "Unavailable");
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
  const [env, services, scenarios] = await Promise.all(
    ["/environments", "/services", "/scenarios"].map((p) => api(p)),
  );
  catalog = { env, services, scenarios };
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
          ["RUN", "ENVIRONMENT", "STATUS", "PERFORMANCE", "STARTED"].map((t) => el("th", {}, t)),
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
async function history() {
  app.append(
    heading(
      "EXECUTION HISTORY",
      "Every run, recorded.",
      "Inspect outcomes, compare inputs, and download execution evidence.",
    ),
  );
  const env = select(
      [["", "All environments"], ...Object.keys(catalog.env).map((x) => [x, x])],
      "",
    ),
    state = select(
      [
        ["", "All outcomes"],
        ...["SUCCEEDED", "FAILED", "CANCELLED", "NEEDS_ATTENTION"].map((x) => [x, x]),
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
  const diagnostics = diagnosticsPanel(api, { runId: id });
  const loads = additionalLoads(
    api,
    { services: catalog.services, environment: initial.environment },
    id,
    plan,
  );
  const status = el("div", { class: "card run-status-card" }),
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
    el(
      "div",
      { class: "run-toolbar" },
      el(
        "a",
        { href: "#monitor/" + id, target: "_blank", rel: "noopener", class: "button primary" },
        "Open monitoring ↗",
      ),
      diagnostics?.toggle,
      actions,
    ),
    status,
    loads?.node,
    el(
      "div",
      { class: "detail-grid spacer" },
      el("details", { class: "card" }, el("summary", {}, "Execution timeline"), timeline),
      el(
        "div",
        {},
        el(
          "section",
          { class: "card" },
          el("h2", {}, "Performance measurements"),
          monitoring,
          metrics,
        ),
        pinnedInputs(plan),
      ),
    ),
  );
  if (diagnostics) {
    app.append(diagnostics.node);
    pageCleanup = () => {
      diagnostics.dispose();
      loads?.dispose();
    };
  }
  let activeDeployment = null,
    timelineElapsed = null;
  let deploymentGroup = null,
    deploymentList = null,
    pendingService = null,
    deploymentSummary = null,
    deploymentTotal = 0;
  const deploymentRows = new Map();
  function serviceState(row, state, detail) {
    row.icon.textContent =
      state === "ready" ? "✓" : state === "failed" ? "✕" : state === "deploying" ? "◌" : "–";
    row.icon.className = `deployment-indicator ${state}`;
    row.icon.setAttribute("aria-label", state === "ready" ? "Deployment succeeded" : state);
    row.detail.textContent = detail;
    row.state = state;
    row.node.setAttribute("data-state", state);
    const states = [...deploymentRows.values()].map((item) => item.state);
    if (deploymentSummary)
      deploymentSummary.textContent =
        `${states.filter((value) => value === "ready").length} of ${deploymentTotal} ready` +
        (states.includes("deploying")
          ? " · Deployment in progress"
          : states.includes("failed")
            ? " · Deployment failed"
            : "");
  }
  function appendTimelineEvent(event) {
    if (timelineElapsed) timelineElapsed.textContent = "";
    timelineElapsed = null;
    if (event.state === "DEPLOYING") {
      if (!deploymentGroup) {
        deploymentList = el("div", { class: "deployment-progress-list" });
        deploymentSummary = el("span", { class: "muted deployment-group-summary" });
        deploymentGroup = el(
          "li",
          {},
          el("time", {}, time(event.time)),
          el(
            "div",
            { class: "deployment-group-heading" },
            el("strong", {}, "DEPLOYING"),
            deploymentSummary,
          ),
          deploymentList,
        );
        timeline.append(deploymentGroup);
      }
      const completed = / ready in (\d+)s$/.exec(event.message);
      const context = event.message.replace(/^Deploying /, "").replace(/ ready in \d+s$/, "");
      if (!/^Service \d+\/\d+: /.test(context)) {
        // Older runs have only a stage-level message and no per-service events.
        if (!deploymentRows.size)
          deploymentList.replaceChildren(el("p", { class: "muted" }, event.message));
        return;
      }
      let row = deploymentRows.get(context);
      if (!row) {
        if (!deploymentRows.size) deploymentList.replaceChildren();
        const parts =
          /^Service (\d+)\/(\d+): (.*?) · (?:image version (.*?) · )?namespace (.*?) · release (.*)$/.exec(
            context,
          );
        const service = parts ? plan.services.find((item) => item.serviceId === parts[3]) : null;
        deploymentTotal = parts
          ? Number(parts[2])
          : Math.max(deploymentTotal, deploymentRows.size + 1);
        row = {
          icon: el("span", { role: "img" }),
          detail: el("span", { class: "deployment-status-label" }),
          timer: el("span", { class: "muted" }),
          duration: el("span", { class: "muted" }),
        };
        const field = (label, value) => el("div", {}, el("dt", {}, label), el("dd", {}, value));
        row.node = el(
          "div",
          { class: "deployment-progress-row" },
          el(
            "div",
            { class: "deployment-service-heading" },
            el(
              "div",
              { class: "deployment-service-identity" },
              row.icon,
              el("strong", {}, parts ? parts[3] : context),
            ),
            parts
              ? el("span", { class: "deployment-position" }, `${parts[1]} / ${parts[2]}`)
              : null,
          ),
          parts
            ? el(
                "dl",
                { class: "deployment-service-fields" },
                field("Image version", parts[4] || service?.image?.version || "Not recorded"),
                field("Namespace", parts[5]),
                field("Helm release", parts[6]),
              )
            : null,
          el("div", { class: "deployment-progress-meta" }, row.detail, row.timer, row.duration),
        );
        deploymentList.append(row.node);
        deploymentRows.set(context, row);
      }
      if (completed) {
        const seconds = Number(completed[1]);
        row.duration.textContent = `Duration: ${Math.floor(seconds / 60)}m ${seconds % 60}s`;
        serviceState(row, "ready", "Ready");
        if (pendingService === row) pendingService = null;
      } else {
        if (pendingService && pendingService !== row)
          serviceState(pendingService, "stopped", "No completion recorded");
        serviceState(row, "deploying", "Deploying");
        pendingService = row;
        timelineElapsed = row.timer;
      }
      return;
    }
    if (pendingService) {
      const failed = /failed|failure/i.test(event.message);
      serviceState(
        pendingService,
        failed ? "failed" : "stopped",
        failed ? "Failed — see details below" : "Interrupted / completion not recorded",
      );
      pendingService = null;
    }
    timelineElapsed = el("span", { class: "muted" });
    timeline.append(
      el(
        "li",
        {},
        el("time", {}, time(event.time)),
        el("strong", {}, event.state.replaceAll("_", " ")),
        el("p", {}, event.message),
        timelineElapsed,
      ),
    );
  }
  const elapsed = el("p", { class: "muted", hidden: true });
  const updateElapsed = () => {
    const active =
      activeDeployment &&
      ["DEPLOYING", "STARTING_LOAD"].includes(activeDeployment.state) &&
      activeDeployment.message.startsWith("Deploying ");
    elapsed.hidden = !active;
    const seconds = active
      ? Math.max(0, Math.floor((Date.now() - Date.parse(activeDeployment.updatedAt)) / 1000))
      : 0;
    const label = active ? `Elapsed: ${Math.floor(seconds / 60)}m ${seconds % 60}s` : "";
    elapsed.textContent = label;
    if (timelineElapsed) timelineElapsed.textContent = label;
  };
  const elapsedTimer = setInterval(updateElapsed, 1000),
    previousCleanup = pageCleanup;
  pageCleanup = () => {
    clearInterval(elapsedTimer);
    previousCleanup?.();
  };
  let cursor = 0;
  const update = async () => {
    const [run, events] = await Promise.all([
      api("/runs/" + id),
      api(`/runs/${id}/events?after=${cursor}`),
    ]);
    if (generation !== routeGeneration) return;
    activeDeployment = run;
    status.replaceChildren(
      el(
        "div",
        { class: "inline" },
        pill(run.state),
        pill(run.verdict),
        el("span", { class: "muted" }, run.message),
      ),
    );
    status.append(elapsed);
    for (const event of events) {
      appendTimelineEvent(event);
      cursor = event.id;
    }
    updateElapsed();
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
    monitoring.textContent = `LogQL measurements · updated ${time(run.updatedAt)}. Missing measurements are unavailable, not zero.`;
    actions.replaceChildren();
    if (!terminal.has(run.state))
      actions.append(
        button(
          "Stop run & all loads",
          async () => {
            if (
              !window.confirm(
                "Stop this run and uninstall all of its load releases? Application services will remain deployed.",
              )
            )
              return;
            await api("/runs/" + id + "/cancel", { method: "POST" });
          },
          "danger",
        ),
      );
    else {
      actions.append(
        link("View report", `/api/v1/runs/${id}/report`, "button"),
        link("JSON summary", `/api/v1/runs/${id}/artifacts/summary.json`, "button"),
      );
      const removable = plan.services.filter(
        (service) =>
          service.serviceId !== plan.effectiveLoadConfiguration.loadService &&
          service.baselineDigest === "ABSENT",
      );
      if (run.state !== "NEEDS_ATTENTION" && removable.length)
        actions.append(
          button(
            "Clean up services",
            async () => {
              const releases = removable
                .map((service) => `${service.namespace} / ${service.releaseName}`)
                .join("\n");
              if (
                !window.confirm(
                  `Uninstall these service releases if they are still owned by this run?\n\n${releases}\n\nServices that existed before this run are retained.`,
                )
              )
                return;
              const result = await api(`/execution/runs/${id}/cleanup-services`, {
                method: "POST",
              });
              toast(`Cleanup confirmed for ${result.removed.length} service release(s).`);
              await update();
            },
            "danger",
          ),
        );
      if (run.state === "NEEDS_ATTENTION")
        actions.append(
          button("Verify cleanup & release", async () => {
            await api("/execution/runs/" + id + "/recover", {
              method: "POST",
            });
            await update();
          }),
        );
    }
    const dashboard = catalog.env[run.environment]?.configuration.dashboardUrl;
    if (dashboard && /^https:\/\//.test(dashboard))
      actions.append(link("Open Grafana", dashboard, "button"));
    await loads?.update(run);
    if (generation !== routeGeneration) return;
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
    ["catalog.environments", "Environments & monitoring"],
    ["catalog.services", "Services, namespaces & releases"],
    ["catalog.scenarios", "Load scenario templates"],
    ["connections.artifactory", "Artifactory connections"],
    ["connections.bitbucket", "Bitbucket connections"],
    ["connections.loki", "Shared Loki connections"],
    ["connections.secretServers", "Secret Server authentication"],
    ["connections.credentials", "Credential references & secret IDs"],
    ["connections.imageSources", "Image-source mappings"],
  ];
  const selector = select(sections, sections[0][0]);
  const editor = el("textarea", {
    rows: 20,
    spellcheck: "false",
    "aria-label": "Configuration JSON",
  });
  let selected = selector.value;
  const value = () => {
    if (selected === "all") return document;
    const [group, section] = selected.split(".");
    return document[group][section];
  };
  const capture = () => {
    if (selected === "all") {
      const parsed = JSON.parse(editor.value);
      if (!parsed || !parsed.catalog || !parsed.connections)
        throw new Error("Complete configuration requires catalog and connections.");
      document = { ...parsed, revision: document.revision };
      return;
    }
    const [group, section] = selected.split(".");
    document[group][section] = JSON.parse(editor.value);
  };
  editor.value = pretty(value());
  selector.addEventListener("change", () => {
    try {
      capture();
      selected = selector.value;
      editor.value = pretty(value());
    } catch {
      selector.value = selected;
      showError("Fix the JSON in this section before switching sections.");
    }
  });
  const status = el(
    "p",
    { class: "muted", role: "status" },
    "Changes apply when saved. Enter secret references only, never passwords or tokens.",
  );
  const save = button(
    "Save configuration",
    async () => {
      save.disabled = true;
      try {
        capture();
        document = await api("/configuration", { method: "PUT", body: document });
        await route();
        toast("Configuration saved. New requests use the updated settings.");
      } finally {
        save.disabled = false;
      }
    },
    "primary",
  );
  const reload = button("Discard edits & reload", async () => {
    document = await api("/configuration");
    editor.value = pretty(value());
    status.textContent = "Loaded the current saved configuration.";
  });
  const file = el("input", {
    type: "file",
    hidden: true,
    accept: ".json,application/json",
    "aria-label": "Import configuration JSON file",
  });
  const fileName = el("span", { class: "muted", role: "status" }, "JSON file · maximum 256 KiB");
  const importButton = el(
    "button",
    { type: "button", onclick: () => file.click() },
    "Import JSON…",
  );
  file.addEventListener("change", async () => {
    const chosen = file.files[0];
    if (!chosen) return;
    try {
      if (chosen.size > 262144) throw new Error("Configuration must be at most 256 KiB.");
      const imported = JSON.parse(await chosen.text());
      if (
        !imported ||
        typeof imported !== "object" ||
        Array.isArray(imported) ||
        Object.keys(imported).some(
          (key) => !["revision", "catalog", "connections"].includes(key),
        ) ||
        !imported.catalog ||
        !imported.connections
      )
        throw new Error("Import a configuration export containing catalog and connections.");
      imported.connections.loki ||= {};
      imported.connections.bitbucket ||= {}; // Older exports did not include Bitbucket.
      for (const [key] of sections.filter(([key]) => key !== "all")) {
        const [group, section] = key.split(".");
        const value = imported[group][section];
        if (!value || typeof value !== "object" || Array.isArray(value))
          throw new Error(`Missing configuration map: ${key}`);
      }
      if (startup.environment && !imported.catalog.environments[startup.environment])
        throw new Error(
          `Keep the default environment in the imported catalog: ${startup.environment}`,
        );
      document = { ...imported, revision: document.revision };
      selected = "all";
      selector.value = "all";
      fileName.textContent = chosen.name;
      editor.value = pretty(value());
      status.textContent =
        "Imported into the editor only. Review all sections, then Save configuration to validate and apply. This replaces the complete runtime configuration.";
    } catch (error) {
      showError(error.message);
    } finally {
      file.value = "";
    }
  });
  const exportSaved = button("Export active configuration", async () => {
    const active = await api("/configuration");
    const url = URL.createObjectURL(new Blob([pretty(active)], { type: "application/json" }));
    const link = el("a", {
      href: url,
      download: "orchestrator-configuration.json",
    });
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    status.textContent =
      "Exported active configuration. Unsaved editor changes are not included. Review internal URLs and any values you entered before sharing.";
  });
  const restore = button("Load startup defaults into editor", async () => {
    const baseline = await api("/configuration/startup");
    document = { ...baseline.configuration, revision: document.revision };
    editor.value = pretty(value());
    status.textContent =
      "Loaded startup configuration into the editor. Review and save to replace runtime settings. Saving clears dashboard overrides so future startup defaults apply. The runtime file remains as an empty override document.";
  });
  return el(
    "details",
    { class: "card spacer" },
    el("summary", {}, "Advanced JSON · import, export & restore"),
    el(
      "p",
      { class: "muted" },
      startup.runtimeOverride
        ? "Active source: startup settings plus saved dashboard changes. Only changed fields override startup defaults."
        : "Active source: startup YAML / Helm settings. No saved runtime override is loaded.",
    ),
    el(
      "p",
      { class: "muted" },
      "Saved settings survive restarts. Active runs retain their prepared inputs; older unsubmitted plans must be prepared again after catalog changes. Connection changes require a fresh Secret Server sign-in.",
    ),
    startup.environment
      ? el(
          "p",
          { class: "muted" },
          `Default environment: ${startup.environment}. Select a target when configuring each run.`,
        )
      : null,
    labeled("Configuration section", selector),
    labeled("JSON", editor),
    status,
    el("div", { class: "card-actions" }, save, reload, exportSaved, restore),
    el("div", { class: "card-actions config-import" }, importButton, fileName, file),
  );
}

async function settings(section) {
  const active = await api("/configuration");
  const startup = await api("/configuration/startup");
  const authStates = await api("/secret-auth");
  app.append(
    heading(
      "WORKSPACE SETTINGS",
      "Connections & catalog",
      "Manage connections and service settings. Changes apply when saved.",
    ),
    el(
      "p",
      { class: "banner" },
      "Manage integration connections, deployment environments, services and monitoring.",
    ),
    el(
      "p",
      { class: "muted" },
      startup.runtimeOverride
        ? "Startup defaults + saved dashboard changes. Export your configuration below to keep a backup."
        : "Showing startup defaults from application.yaml and local / Helm overrides.",
    ),
    configurationManager(active, {
      api,
      authStates,
      onSaved: async () => {
        await route();
        toast("Settings updated.");
      },
      onSignIn: (id) => busy(() => vaultPrompt.open(id)),
      onConnectionSession: (kind, id) => {
        const auth = operationAuthentication(active.connections[kind][id], { api, kind, id });
        const dialog = el(
          "dialog",
          { class: "deployment-dialog auth-dialog", "aria-label": "Connection session" },
          el("h2", {}, `Connection: ${id}`),
          auth.node,
          el(
            "div",
            { class: "dialog-actions" },
            el("button", { type: "button", onclick: () => dialog.close() }, "Close"),
          ),
        );
        dialog.addEventListener("close", () => {
          auth.clear();
          dialog.remove();
        });
        document.body.append(dialog);
        dialog.showModal();
      },
    }),
  );
  app.append(await configurationEditor());
  const diagnostics = el(
    "details",
    { class: "card spacer" },
    el("summary", {}, "Connection diagnostics"),
    el(
      "p",
      { class: "muted" },
      "Test registry tags and Bitbucket references with configured connections. These requests do not deploy anything.",
    ),
  );
  app.append(diagnostics);
  const config = await api("/connections");
  const panel = connectionDiagnostics(active.catalog, config, api);
  diagnostics.append(panel.node);
  if (section === "diagnostics") {
    diagnostics.open = true;
    diagnostics.scrollIntoView({ block: "start" });
  }
  pageCleanup = () => panel.dispose();
}
function overview() {
  const enabled = session.capabilities.execution;
  app.append(
    heading(
      "PERFORMANCE ORCHESTRATOR",
      "Performance workspace",
      "Repository-backed Helm deployments and read-only service diagnostics.",
    ),
    el(
      "section",
      { class: "card" },
      el("h2", {}, enabled ? "Run a performance test" : "Execution needs cluster configuration"),
      el(
        "p",
        {},
        enabled
          ? "Prepare service charts and a load profile from CKP directories, review pinned inputs, then deploy and run. Owned load is uninstalled at completion; services remain."
          : "Configure orchestrator.execution.enabled, kube-context and expected-api-server, then restart. Git, Helm and kubectl must be installed on the application host.",
      ),
      el(
        "div",
        { class: "card-actions" },
        link("Configure run", "#configure", "button primary"),
        link("Browse service diagnostics", "#settings/diagnostics", "button"),
        link("Configure connections", "#settings", "button"),
      ),
    ),
    el(
      "section",
      { class: "card spacer" },
      el("h2", {}, "Measurement requirements"),
      el(
        "p",
        {},
        "Load rates and destinations come from selected chart values. Supply organization-approved LogQL queries to measure actual traffic. Without measurements and thresholds, the performance verdict is inconclusive.",
      ),
      link("View run history", "#history", "button"),
    ),
  );
}

async function route() {
  pageCleanup();
  pageCleanup = () => {};
  clearTimeout(poll);
  const generation = ++routeGeneration;
  app.replaceChildren(el("p", { class: "muted" }, "Loading workspace…"));
  const [page = "dashboard", id] = (location.hash.slice(1) || "dashboard").split("/");
  document
    .querySelectorAll("nav a")
    .forEach((a) => a.classList.toggle("active", a.hash === "#" + page));
  try {
    await loadCatalog();
    if (generation !== routeGeneration) return;
    app.replaceChildren();
    if (page === "configure") {
      app.append(
        heading(
          "RUN CONFIGURATION",
          "Configure run",
          "Prepare and review the deployment before starting.",
        ),
      );
      const flow = await runBuilder(
        api,
        {
          services: catalog.services,
          environment: id ? decodeURIComponent(id) : session.targetEnvironment,
          environments: Object.keys(catalog.env),
        },
        (hash) => {
          location.hash = hash;
        },
      );
      if (generation !== routeGeneration) {
        flow.dispose?.();
        return;
      }
      app.append(flow);
      pageCleanup = () => flow.dispose?.();
    } else if (page === "plan") {
      const prepared = await api("/plans/" + id);
      app.append(
        heading("PREPARED PLAN", prepared.profile.name, prepared.clusterIdentity),
        el(
          "ul",
          {},
          prepared.warnings.map((warning) => el("li", {}, warning)),
        ),
        el(
          "pre",
          {},
          pretty(
            prepared.services.map((s) => ({
              service: s.serviceId,
              namespace: s.namespace,
              release: s.releaseName,
              commit: s.sourceRevision,
              image: s.image,
              effectiveValues: s.effectiveValues,
            })),
          ),
        ),
        link("Prepare another run", "#configure", "button"),
      );
    } else if (!["settings", "history", "run", "monitor"].includes(page)) overview();
    else if (page === "history") await history();
    else if (page === "monitor") {
      const view = await runMonitoring(api, id, {
        services: catalog.services,
        environment: session.targetEnvironment,
      });
      if (generation !== routeGeneration) {
        view.dispose();
        return;
      }
      app.append(view);
      pageCleanup = () => view.dispose();
    } else if (page === "run") await runDetails(id, generation);
    else if (page === "settings") await settings(id);
    else overview();
    await vaultPrompt?.refresh();
  } catch (error) {
    showError(error.message);
  }
}
session = await api("/session");
document.documentElement.dataset.styleNonce = session.styleNonce;
vaultPrompt = secretSignInPrompt(
  api,
  async (state) => {
    toast(
      state.state === "AUTHENTICATED"
        ? `Signed in as ${state.username} until ${time(state.expiresAt)}.`
        : `Vault token stored until ${time(state.expiresAt)}; access is verified when a secret is requested.`,
    );
    await route();
  },
  true,
);
$("#execution-badge").textContent = session.capabilities.execution
  ? "EXECUTION ENABLED"
  : "READ-ONLY";
$("#workspace-environment").textContent = `Default environment: ${session.targetEnvironment}`;
window.addEventListener("hashchange", route);
await route();

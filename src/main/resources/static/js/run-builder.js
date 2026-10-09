import { confirmEnvironmentCleanup } from "./environment-cleanup.js";
import { monitoringConfig } from "./monitoring-config.js";
import { diagnosticsPanel } from "./diagnostics.js";
import { reviewProgress } from "./review-progress.js";
import { deploymentDialog } from "./deployment-dialog.js";
import { el, input, labeled, select } from "./dom.js";

export async function runBuilder(api, catalog, navigate) {
  const settings = await api("/execution/settings");
  const root = el("div", { class: "run-builder" });
  let disposed = false,
    activeDialog;
  root.dispose = () => {
    disposed = true;
    activeDialog?.close();
    activeDialog?.remove();
  };
  if (!settings.enabled) {
    root.append(
      el(
        "section",
        { class: "card" },
        el("h2", {}, "Enable execution to configure a run"),
        el(
          "p",
          {},
          "Execution is currently disabled. In application.yaml, set orchestrator.execution.enabled to true and configure orchestrator.execution.targets for the environments you want to use, including kube-context and expected-api-server. Then restart the application.",
        ),
        el(
          "p",
          { class: "muted" },
          "For the default environment, the top-level orchestrator.execution.kube-context and expected-api-server settings are also supported. Git, Helm and kubectl must be available to the application process.",
        ),
        el("a", { href: "#settings", class: "button" }, "Connections & catalog"),
      ),
    );
    return root;
  }
  const ids = Object.keys(catalog.services)
    .filter((id) => catalog.services[id].sourceProject && catalog.services[id].containerImage)
    .sort();
  if (!ids.length) {
    root.append(
      el(
        "p",
        { class: "card" },
        "Add service Git, image and Helm settings in Connections & catalog first.",
      ),
    );
    return root;
  }
  const environmentPicker = select(
    (catalog.environments || [catalog.environment]).map((id) => [id, id]),
    catalog.environment,
  );
  environmentPicker.addEventListener("change", () => {
    if (
      window.confirm(
        "Switch environment? Unsaved selections will be cleared; saved profiles are kept.",
      )
    )
      navigate(`#configure/${encodeURIComponent(environmentPicker.value)}`);
    else environmentPicker.value = catalog.environment;
  });
  const cluster = settings.targets?.[catalog.environment];
  const context =
    catalog.environment === settings.defaultEnvironment && settings.kubeContext
      ? settings.kubeContext
      : cluster?.kubeContext;
  const name = input("Performance test"),
    duration = input("60", "number", { min: 1 });
  const warmup = input("0", "number", { min: 0 }),
    deadline = input(String(settings.defaultRunDurationSeconds ?? 900), "number", {
      min: 60,
      max: settings.maxRunDurationSeconds ?? 28800,
    });
  const rows = el("div", { class: "run-selections" }),
    loadSummary = el("div"),
    preview = el("section");
  const status = el("p", {
      role: "status",
      class: "run-status",
      "aria-live": "polite",
      hidden: true,
    }),
    error = el("p", { role: "alert", class: "error run-error", tabindex: "-1", hidden: true });
  const notices = el("div", { class: "run-notices", hidden: true }, error, status);
  const errorObserver = new MutationObserver(() => {
    error.hidden = !error.textContent;
    status.hidden = !status.textContent || !!error.textContent;
    notices.hidden = error.hidden && status.hidden;
    if (!notices.hidden) notices.scrollIntoView({ block: "nearest", behavior: "smooth" });
    if (error.textContent) error.focus({ preventScroll: true });
  });
  errorObserver.observe(error, { childList: true, characterData: true, subtree: true });
  errorObserver.observe(status, { childList: true, characterData: true, subtree: true });
  const disposeBase = root.dispose;
  root.dispose = () => {
    errorObserver.disconnect();
    disposeBase();
  };
  const diagnostics = diagnosticsPanel(api);
  const progress = reviewProgress(api);

  const disposeDiagnostics = root.dispose;
  root.dispose = () => {
    progress.dispose();
    diagnostics.dispose();
    disposeDiagnostics();
  };
  let selections = [],
    load = null,
    plan = null,
    savedId = null,
    savedRevision = null,
    working = false;
  const invalidate = () => {
    plan = null;
    preview.replaceChildren();
    status.textContent = "";
  };
  const connections = await api("/connections");
  const monitoring = monitoringConfig(catalog, connections.credentialReferences, invalidate, {
    evaluation: true,
    api,
  });
  const disposeCurrent = root.dispose;
  root.dispose = () => {
    monitoring.dispose();
    disposeCurrent();
  };
  const destination = (id) =>
    catalog.services[id]?.deploymentByEnvironment?.[catalog.environment] ||
    catalog.services[id]?.deploymentDefaults;
  const button = (text, action, primary = false) =>
    el("button", { type: "button", class: primary ? "primary" : "", onclick: action }, text);

  let draggedService = null;
  function moveService(from, to, focus = false) {
    if (
      working ||
      from < 0 ||
      to < 0 ||
      from >= selections.length ||
      to >= selections.length ||
      from === to
    )
      return;
    const [service] = selections.splice(from, 1);
    selections.splice(to, 0, service);
    invalidate();
    renderSelections();
    status.textContent = `${service.serviceId} moved to position ${to + 1}.`;
    if (focus) rows.children[to]?.querySelector(".service-drag-handle")?.focus();
  }
  function summary(data, isLoad, index) {
    const dest = destination(data.serviceId);
    const orderControls = isLoad
      ? null
      : el(
          "div",
          { class: "deployment-actions" },
          el(
            "button",
            {
              type: "button",
              class: "service-drag-handle",
              draggable: "true",
              title: "Drag to reorder, or use Move up/down",
              "aria-label": `Reorder ${data.serviceId}, position ${index + 1}`,
              ondragstart: (event) => {
                if (working) {
                  event.preventDefault();
                  return;
                }
                draggedService = data.serviceId;
                event.dataTransfer.effectAllowed = "move";
                event.dataTransfer.setData("text/plain", data.serviceId);
              },
              ondragend: () => {
                draggedService = null;
                rows
                  .querySelectorAll(".service-drop-target")
                  .forEach((node) => node.classList.remove("service-drop-target"));
              },
            },
            "⠿",
          ),
          el(
            "button",
            {
              type: "button",
              disabled: index === 0,
              "aria-label": `Move ${data.serviceId} up`,
              onclick: () => moveService(index, index - 1, true),
            },
            "↑",
          ),
          el(
            "button",
            {
              type: "button",
              disabled: index === selections.length - 1,
              "aria-label": `Move ${data.serviceId} down`,
              onclick: () => moveService(index, index + 1, true),
            },
            "↓",
          ),
        );
    const row = el(
      "article",
      { class: "run-selection" },
      el(
        "div",
        {},
        el("strong", {}, isLoad ? data.serviceId : `${index + 1}. ${data.serviceId}`),
        el(
          "p",
          { class: "muted" },
          `Image ${data.imageVersion} · Git ${data.gitReference || "prepared revision"}`,
        ),
        el(
          "p",
          { class: "muted" },
          `${dest?.namespace || "Unconfigured namespace"} · ${data.valuesFiles.length} values file(s)${Object.keys(data.valuesEdits || {}).length || data.overlay ? " · edited" : ""}`,
        ),
      ),
      el(
        "div",
        { class: "deployment-actions" },
        orderControls,
        button("Edit", () => editDeployment(isLoad, index)),
        button("Remove", () => {
          if (isLoad) load = null;
          else selections.splice(index, 1);
          invalidate();
          renderSelections();
        }),
      ),
    );
    if (!isLoad) {
      row.addEventListener("dragover", (event) => {
        if (!working && draggedService && draggedService !== data.serviceId) {
          event.preventDefault();
          event.dataTransfer.dropEffect = "move";
          row.classList.add("service-drop-target");
        }
      });
      row.addEventListener("dragleave", (event) => {
        if (!row.contains(event.relatedTarget)) row.classList.remove("service-drop-target");
      });
      row.addEventListener("drop", (event) => {
        event.preventDefault();
        row.classList.remove("service-drop-target");
        if (draggedService)
          moveService(
            selections.findIndex((service) => service.serviceId === draggedService),
            index,
            true,
          );
        draggedService = null;
      });
    }
    return row;
  }
  function renderSelections() {
    rows.replaceChildren(
      ...(selections.length
        ? selections.map((data, index) => summary(data, false, index))
        : [
            el(
              "p",
              { class: "muted" },
              "No service deployments added. Add the services you want to update before testing.",
            ),
          ]),
    );
    loadSummary.replaceChildren(
      load
        ? summary(load, true, 0)
        : el(
            "p",
            { class: "muted" },
            "Choose the load generator, image version and traffic values file.",
          ),
    );
    chooseLoad.hidden = !!load;
  }

  function editDeployment(isLoad, index) {
    const original = isLoad ? load : selections[index];
    const used = new Set(
      selections.filter((_, i) => isLoad || i !== index).map((s) => s.serviceId),
    );
    if (!isLoad && load) used.add(load.serviceId);
    const available = ids.filter((id) => !used.has(id));
    if (!available.length) {
      error.textContent = "All configured services have already been selected.";
      return;
    }
    activeDialog = deploymentDialog(api, catalog, {
      available,
      original,
      isLoad,
      onSave: (data) => {
        if (isLoad) load = data;
        else if (original) selections[index] = data;
        else selections.push(data);
        invalidate();
        renderSelections();
      },
    });
  }

  const add = button("Add service", () => editDeployment(false, -1));
  const chooseLoad = button("Choose load generator", () => editDeployment(true, 0));
  const savedProfiles = await api(
    `/execution/profiles?environment=${encodeURIComponent(catalog.environment)}`,
  );
  const savedSelect = select(
    [["", "New profile"], ...savedProfiles.map((p) => [p.id, p.profile.name])],
    "",
  );
  const readProfile = () => {
    if (!load) throw new Error("Choose a load generator before saving or preparing this run.");
    return {
      targetEnvironment: catalog.environment,
      name: name.value,
      services: selections,
      loadGenerator: load,
      warmupSeconds: Number(warmup.value),
      measurementSeconds: Number(duration.value),
      maxRunDurationSeconds: Number(deadline.value),
      metrics: monitoring.read(),
      thresholds: monitoring.readThresholds(),
    };
  };
  savedSelect.addEventListener("change", () => {
    const saved = savedProfiles.find((p) => p.id === savedSelect.value);
    savedId = saved?.id || null;
    savedRevision = saved?.revision || null;
    invalidate();
    const p = saved?.profile;
    name.value = p?.name || "Performance test";
    warmup.value = p?.warmupSeconds ?? 0;
    duration.value = p?.measurementSeconds ?? 60;
    deadline.value = p?.maxRunDurationSeconds ?? settings.defaultRunDurationSeconds ?? 900;
    selections = structuredClone(p?.services || []);
    load = p?.loadGenerator ? structuredClone(p.loadGenerator) : null;
    monitoring.set(p?.metrics || [], p?.thresholds || []);
    renderSelections();
  });
  const saveProfile = button("Save profile", async () => {
    saveProfile.disabled = true;
    error.textContent = "";
    status.textContent = "Saving run profile…";
    try {
      const result = await api("/execution/profiles", {
        method: "POST",
        body: { id: savedId, revision: savedRevision, profile: readProfile() },
      });
      if (disposed) return;
      savedId = result.id;
      savedRevision = result.revision;
      const index = savedProfiles.findIndex((p) => p.id === result.id);
      if (index < 0) savedProfiles.push(result);
      else savedProfiles[index] = result;
      savedSelect.replaceChildren(
        el("option", { value: "" }, "New profile"),
        ...savedProfiles.map((p) => el("option", { value: p.id }, p.profile.name)),
      );
      savedSelect.value = result.id;
      status.textContent = "Profile saved.";
    } catch (reason) {
      error.textContent = reason.message;
      status.textContent = "";
    } finally {
      saveProfile.disabled = false;
    }
  });
  const cleanupFirst = input("", "checkbox");
  cleanupFirst.addEventListener("change", invalidate);
  const prepare = button(
    "Review run",
    async () => {
      if (working) return;
      working = true;
      error.textContent = "";
      invalidate();
      const controls = [
        ...root.querySelectorAll("input,select,textarea,button:not([data-during-preparation])"),
      ];
      controls.forEach((node) => (node.disabled = true));
      status.textContent = "Preparing charts and checking the cluster…";
      let reviewStarted = false;
      try {
        const profile = readProfile();
        const trace = await diagnostics.start();
        if (cleanupFirst.checked) {
          status.textContent = "Checking selected releases for cleanup…";
          const headers = trace ? { "X-Diagnostic-ID": trace } : {};
          const cleanup = await api("/execution/environment-cleanup/preview", {
            method: "POST",
            headers,
            body: {
              environment: profile.targetEnvironment,
              services: [
                ...profile.services.map((service) => service.serviceId),
                profile.loadGenerator.serviceId,
              ],
            },
          });
          if (disposed) return;
          const confirmedCleanup = await confirmEnvironmentCleanup(cleanup, (dialog) => {
            activeDialog = dialog;
          });
          activeDialog = null;
          if (!confirmedCleanup || disposed) {
            status.textContent = "Cleanup cancelled. No review was started.";
            return;
          }
          status.textContent = "Uninstalling confirmed releases and verifying cleanup…";
          await api("/execution/environment-cleanup", {
            method: "POST",
            headers,
            body: { previewId: cleanup.id },
          });
          if (disposed) return;
          cleanupFirst.checked = false;
        }
        status.textContent = "Preparing charts and checking the cluster…";
        const reviewId = progress.start(profile);
        reviewStarted = true;
        plan = await api("/execution/plans", {
          method: "POST",
          headers: { ...(trace ? { "X-Diagnostic-ID": trace } : {}), "X-Review-ID": reviewId },
          body: profile,
        });
        if (disposed) return;
        const prepared = plan,
          confirmed = el("input", { type: "checkbox" }),
          key = crypto.randomUUID();
        const run = button(
          "Start run",
          async () => {
            if (plan !== prepared || !confirmed.checked) {
              error.textContent = "Review and confirm this plan before starting.";
              return;
            }
            run.disabled = true;
            error.textContent = "";
            status.textContent = "Submitting the run…";
            try {
              const result = await api("/execution/runs", {
                method: "POST",
                headers: { "Idempotency-Key": key },
                body: { planId: prepared.id },
              });
              if (!disposed) navigate(`#run/${result.id}`);
            } catch (reason) {
              error.textContent = reason.message;
              status.textContent = "";
              run.disabled = false;
            }
          },
          true,
        );
        preview.className = "card";
        preview.append(
          el("h2", {}, "Review run"),
          ...plan.services.map((s) =>
            el(
              "div",
              { class: "run-selection" },
              el(
                "div",
                {},
                el("strong", {}, s.serviceId),
                el("p", {}, `${s.namespace} / ${s.releaseName}`),
                el(
                  "p",
                  { class: "muted" },
                  `Image ${s.image.version} · commit ${s.sourceRevision.slice(0, 12)}`,
                ),
                el(
                  "details",
                  {},
                  el("summary", {}, "Prepared values"),
                  el("pre", {}, JSON.stringify(s.effectiveValues, null, 2)),
                ),
              ),
            ),
          ),
          el(
            "details",
            {},
            el("summary", {}, "Execution details"),
            el(
              "ul",
              {},
              plan.warnings.map((text) => el("li", {}, text)),
            ),
          ),
          labeled(
            "I reviewed the target, services and values. Start this deployment and load test.",
            confirmed,
          ),
          run,
        );
        status.textContent = "Plan ready for review. Nothing has been deployed yet.";
      } catch (reason) {
        error.textContent = reason.message;
        status.textContent = "";
      } finally {
        if (reviewStarted) await progress.finish(!!plan);
        working = false;
        controls.forEach((node) => (node.disabled = false));
        renderSelections();
      }
    },
    true,
  );
  root.append(
    notices,
    progress.node,
    el(
      "section",
      { class: "card" },
      el(
        "div",
        { class: "form-grid" },
        labeled("Environment", environmentPicker),
        labeled("Saved profile", savedSelect),
        labeled("Run name", name),
      ),
      el(
        "p",
        { class: "muted" },
        `Environment: ${catalog.environment} · Context: ${context || "Not configured — add an execution target before review"}`,
      ),
    ),
    el(
      "section",
      { class: "card" },
      el("div", { class: "dialog-heading" }, el("h2", {}, "Services"), add),
      el(
        "p",
        { class: "muted" },
        "Deploys from top to bottom. Drag the handle or use the arrows to reorder. The load generator starts after all services are ready.",
      ),
      rows,
    ),
    el(
      "section",
      { class: "card" },
      el("div", { class: "dialog-heading" }, el("h2", {}, "Load generator"), chooseLoad),
      loadSummary,
    ),
    el(
      "section",
      { class: "card" },
      el("h2", {}, "Test duration"),
      labeled("Measurement duration (seconds)", duration),
      el(
        "p",
        { class: "muted" },
        "How long to observe traffic after warmup. Results use this window; when it ends, the orchestrator collects metrics and stops its load generator. Traffic rate and the tool’s own duration come from its values YAML.",
      ),
      el(
        "details",
        {},
        el("summary", {}, "Advanced timing"),
        el(
          "div",
          { class: "form-grid spacer" },
          labeled("Warmup (seconds)", warmup),
          labeled("Overall timeout (seconds)", deadline),
        ),
        el(
          "p",
          { class: "muted" },
          `Warmup is excluded from measurements. The overall timeout includes deployment, warmup and measurement. Each Helm operation has a ${settings.commandTimeoutSeconds ?? 300}s timeout. Services deploy sequentially; allow time for all services plus load installation and measurement. Cleanup and an in-progress command can extend past the overall deadline. Set the load YAML duration to cover warmup plus measurement.`,
        ),
      ),
    ),
    monitoring.node,
    el(
      "div",
      {},
      el(
        "p",
        { class: "muted" },
        "Loads stay installed after measurement until stopped manually. Failures, restart, and the overall deadline still trigger load cleanup. Services remain deployed unless explicitly cleaned up.",
      ),
      el(
        "label",
        { class: "cleanup-confirmation" },
        cleanupFirst,
        "Clean up selected releases before review (confirmation required)",
      ),
      el(
        "p",
        { class: "muted" },
        "Optional: remove the selected services’ and load generator’s configured Helm releases, including pre-existing installations. Unrelated releases and dynamically named loads are not included. Active runs block cleanup.",
      ),
      el("div", { class: "card-actions" }, saveProfile, prepare, diagnostics.toggle),
    ),
    preview,
    diagnostics.node,
  );
  for (const field of [name, warmup, duration, deadline])
    field.addEventListener("input", invalidate);
  renderSelections();
  return root;
}

import { deploymentDialog } from "./deployment-dialog.js";
import { el } from "./dom.js";

export function additionalLoads(api, catalog, runId, plan) {
  let disposed = false,
    dialog,
    selected,
    reviewed,
    busy = false,
    active = false;
  const rows = el("div", { class: "run-selections" }),
    preview = el("div"),
    status = el("p", { role: "status", class: "run-status run-notices", hidden: true });
  const error = el("p", { role: "alert", class: "error run-error", tabindex: "-1", hidden: true });
  const button = (label, action) => el("button", { type: "button", onclick: action }, label);
  const available = Object.keys(catalog.services)
    .filter((id) => catalog.services[id].sourceProject && catalog.services[id].containerImage)
    .sort();
  const add = button("Add load", () => {
    dialog = deploymentDialog(api, catalog, {
      available,
      onSave: (data) => {
        selected = data;
        reviewed = null;
        renderReview();
      },
    });
  });
  add.disabled = true;
  const node = el(
    "section",
    { class: "card spacer" },
    el("div", { class: "dialog-heading" }, el("h2", {}, "Load installations"), add),
    el(
      "p",
      { class: "muted" },
      "Add a different traffic pattern alongside the baseline. Each addition uses a new Helm release. Stop each load independently, or stop all loads to end the run. Measurement completion does not uninstall loads. Failures, restart, and the overall deadline still trigger load cleanup. Additional loads do not extend that deadline.",
    ),
    status,
    error,
    preview,
    rows,
  );
  function fail(reason) {
    error.hidden = false;
    error.textContent = reason.message;
    error.focus();
  }
  async function operation(label, work) {
    busy = true;
    add.disabled = true;
    error.hidden = true;
    status.hidden = false;
    status.textContent = label;
    renderReview();
    status.scrollIntoView({ block: "nearest" });
    try {
      await work();
    } catch (reason) {
      if (!disposed) fail(reason);
    } finally {
      busy = false;
      if (!disposed) {
        status.hidden = true;
        add.disabled = !active || !available.length;
        renderReview();
      }
    }
  }
  function renderReview() {
    preview.replaceChildren();
    if (!selected) return;
    const start = button(reviewed ? "Install additional load" : "Review additional load", () =>
      operation(
        reviewed
          ? "Queuing additional load…"
          : "Preparing the additional chart, dependencies and values…",
        async () => {
          if (reviewed) {
            await api(`/execution/runs/${runId}/loads/${reviewed.id}/start`, { method: "POST" });
            selected = null;
            reviewed = null;
          } else
            reviewed = await api(`/execution/runs/${runId}/loads/review`, {
              method: "POST",
              body: selected,
            });
        },
      ),
    );
    start.disabled = busy || !active;
    const discard = button("Discard", () => {
      selected = null;
      reviewed = null;
      renderReview();
    });
    discard.disabled = busy;
    preview.append(
      el(
        "article",
        { class: "run-selection" },
        el(
          "div",
          {},
          el("strong", {}, selected.serviceId),
          el("p", {}, `Image ${selected.imageVersion}`),
          reviewed
            ? el(
                "p",
                {},
                `New release: ${reviewed.service.releaseName} · Namespace: ${reviewed.service.namespace}`,
              )
            : null,
          el(
            "p",
            { class: "muted" },
            "The chart must use distinct resource names for each Helm release. Existing loads and service installations are retained.",
          ),
          reviewed
            ? el(
                "details",
                {},
                el("summary", {}, "Review effective values"),
                el("pre", {}, JSON.stringify(reviewed.service.effectiveValues, null, 2)),
              )
            : null,
        ),
        el("div", { class: "deployment-actions" }, discard, start),
      ),
    );
  }
  return {
    node,
    async update(run) {
      const [loads, baselineStatus] = await Promise.all([
        api(`/execution/runs/${runId}/loads`),
        api(`/execution/runs/${runId}/loads/baseline`),
      ]);
      if (disposed) return;
      const wasActive = active;
      active =
        ["RUNNING_LOAD", "AWAITING_LOAD_STOP"].includes(run.state) &&
        Date.now() < Date.parse(run.createdAt) + plan.profile.maxRunDurationSeconds * 1000;
      add.disabled = busy || !active || !available.length;
      const baseline = plan.services.find(
        (service) => service.serviceId === plan.effectiveLoadConfiguration.loadService,
      );
      const stopButton = (loadId, service) => {
        const stop = button("Stop this load", () => {
          if (
            !window.confirm(
              `Uninstall load release ${service.releaseName} in ${service.namespace}? Other loads and application services will keep running.`,
            )
          )
            return;
          operation(`Stopping ${service.releaseName}…`, async () => {
            await api(`/execution/runs/${runId}/loads/${loadId}/stop`, { method: "POST" });
            await this.update(run);
          });
        });
        stop.disabled = busy || !active;
        return stop;
      };
      const row = (title, service, state, message, loadId, canStop) =>
        el(
          "article",
          { class: "run-selection" },
          el(
            "div",
            {},
            el("strong", {}, title),
            el("p", {}, `${service.serviceId} · Image ${service.image.version}`),
            el("p", { class: "muted" }, `${service.namespace} / ${service.releaseName}`),
            el("p", { class: "muted" }, message),
          ),
          el(
            "div",
            { class: "deployment-actions" },
            el(
              "span",
              { class: /FAILED|REJECTED|NEEDS_ATTENTION/.test(state) ? "pill bad" : "pill" },
              state.replaceAll("_", " "),
            ),
            canStop && active ? stopButton(loadId, service) : null,
          ),
        );
      rows.replaceChildren(
        ...(baseline
          ? [
              row(
                "Baseline",
                baseline,
                baselineStatus.stopped ? "STOPPED" : active ? "INSTALLED" : run.state,
                baselineStatus.stopped
                  ? "This load release has been uninstalled"
                  : "Part of the original run plan; traffic duration is controlled by its YAML",
                "baseline",
                !baselineStatus.stopped,
              ),
            ]
          : []),
        ...loads
          .filter((load) => load.state !== "REVIEWED")
          .map((load, index) =>
            row(
              `Additional load ${index + 1}`,
              load.service,
              load.state,
              `${load.message} · Added by ${load.actor}`,
              load.id,
              !["STOPPED", "NOT_STARTED", "FAILED_STOPPED", "REJECTED"].includes(load.state),
            ),
          ),
      );
      if (wasActive !== active) renderReview();
    },
    dispose() {
      disposed = true;
      dialog?.close();
      dialog?.remove();
    },
  };
}

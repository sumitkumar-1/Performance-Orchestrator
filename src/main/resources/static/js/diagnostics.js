import { el, select, labeled } from "./dom.js";

export function diagnosticsPanel(api, { runId } = {}) {
  let trace = null,
    closed = false,
    timer,
    busy = false,
    latest = "",
    enabled = false;
  const consoleView = el("pre", {
    class: "diagnostic-console",
    tabindex: "0",
    "aria-label": "Command and API diagnostic console",
  });
  const error = el("p", { class: "error", role: "alert", hidden: true });
  const picker = select([["", "Select a preparation attempt"]], ""),
    status = el("p", { class: "muted", role: "status" });
  const live = el("input", { type: "checkbox", checked: true, "data-during-preparation": true }),
    follow = el("input", { type: "checkbox", checked: true, "data-during-preparation": true });
  const control = (label, onclick) =>
    el("button", { type: "button", "data-during-preparation": true, onclick }, label);
  const node = el(
    "section",
    { class: "card diagnostic-panel", hidden: true },
    el("h2", {}, "Diagnostics"),
    el(
      "p",
      { class: "muted" },
      "Optional diagnostic view. Commands, paths, namespaces and endpoints are visible; credential-bearing values are masked. Bodies, stdin, environment variables and raw output are not recorded. Latest 500 operations, oldest first.",
    ),
    runId ? null : labeled("Recent attempts", picker),
    el(
      "div",
      { class: "card-actions" },
      labeled("Live refresh", live),
      labeled("Follow latest", follow),
      control("Refresh", () => refresh(true)),
      control("Copy console", async () => {
        try {
          await navigator.clipboard.writeText(latest);
          status.textContent = "Console copied.";
        } catch (reason) {
          fail(reason);
        }
      }),
    ),
    status,
    error,
    consoleView,
  );
  async function attempts() {
    if (runId) return;
    try {
      const list = await api("/real/diagnostics");
      if (closed) return;
      picker.replaceChildren(
        el("option", { value: "" }, "Select a preparation attempt"),
        ...list.map((t) =>
          el(
            "option",
            { value: t.id },
            `${new Date(t.createdAt).toLocaleString()} · ${t.runId ? "Run submission" : t.planId ? "Prepared" : "Preparation attempt"} · ${t.id.slice(0, 8)}`,
          ),
        ),
      );
      picker.value = trace || "";
    } catch (reason) {
      fail(reason);
    }
  }
  function fail(reason) {
    if (!closed) {
      error.hidden = false;
      error.textContent = reason.message;
    }
  }
  async function refresh(manual = false) {
    clearTimeout(timer);
    if (closed || node.hidden || busy || (!manual && !live.checked)) return;
    if (!runId && !trace) {
      status.textContent = "Choose a previous attempt, or review a run to begin recording.";
      return;
    }
    busy = true;
    try {
      const selected = trace;
      const data = await api(
        runId
          ? `/real/runs/${encodeURIComponent(runId)}/diagnostics`
          : `/real/diagnostics/${encodeURIComponent(trace)}`,
      );
      if (closed || selected !== trace) return;
      error.hidden = true;
      latest = data
        .slice()
        .reverse()
        .map(
          (op) =>
            `${op.startedAt}  [${op.kind}]  ${op.summary}\n  => ${op.outcome || "RUNNING"}${op.durationMs == null ? " · completion not yet recorded" : ` · ${op.durationMs} ms`}`,
        )
        .join("\n\n");
      const scroll = consoleView.scrollTop;
      consoleView.textContent = latest || "No recorded operations.";
      consoleView.scrollTop = follow.checked ? consoleView.scrollHeight : scroll;
      status.textContent = `${data.length} operations${live.checked ? " · Refreshing every 2 seconds." : " · Live refresh paused."} Recording is limited to 5,000 operations per attempt.`;
    } catch (reason) {
      fail(reason);
    } finally {
      busy = false;
      if (!closed && !node.hidden && live.checked) timer = setTimeout(refresh, 2000);
    }
  }
  picker.addEventListener("change", () => {
    trace = picker.value || null;
    consoleView.textContent = "";
    refresh(true);
  });
  live.addEventListener("change", () => {
    clearTimeout(timer);
    if (live.checked) refresh();
    else status.textContent = "Live refresh paused; recording continues.";
  });
  const toggle = control("Show diagnostics", () => {
    if (node.hidden) show();
    else {
      node.hidden = true;
      clearTimeout(timer);
      toggle.textContent = "Show diagnostics";
      toggle.setAttribute("aria-expanded", "false");
    }
  });
  toggle.hidden = true;
  toggle.setAttribute("aria-expanded", "false");
  function show() {
    if (!enabled || closed) return;
    node.hidden = false;
    toggle.textContent = "Hide diagnostics";
    toggle.setAttribute("aria-expanded", "true");
    attempts();
    refresh(true);
  }
  const ready = api("/real/diagnostics/settings")
    .then((settings) => {
      enabled = !!settings.enabled;
      if (!closed) toggle.hidden = !enabled;
    })
    .catch(() => {
      enabled = false;
    });
  return {
    node,
    toggle,
    show,
    start: async () => {
      await ready;
      if (!enabled || closed) return null;
      const result = await api("/real/diagnostics", { method: "POST" });
      if (closed) return result.id;
      trace = result.id || null;
      if (!node.hidden) {
        await attempts();
        refresh(true);
      }
      return trace;
    },
    dispose: () => {
      closed = true;
      clearTimeout(timer);
    },
  };
}

import { el, input, labeled, select } from "./dom.js";

export function monitoringLibrary(api, editor, onChange, { evaluation, environment }) {
  const scope = `environment=${encodeURIComponent(environment || "")}`;
  let sets = [],
    disposed = false,
    busy = false,
    dialog;
  const picker = select([["", "Choose a saved monitoring set"]], ""),
    status = el("p", { role: "status", class: "muted" });
  const error = el("p", { role: "alert", class: "error", hidden: true });
  const button = (text, onclick) => el("button", { type: "button", onclick }, text);
  const selected = () => sets.find((s) => s.id === picker.value);
  const load = button("Load set", () => {
    const saved = selected();
    if (!saved) return;
    if (
      editor.read().length &&
      !window.confirm("Replace the current monitoring panels with this saved set?")
    )
      return;
    editor.set(saved.definition.panels, saved.definition.thresholds || []);
    onChange();
    status.textContent = evaluation
      ? "Monitoring set loaded. It will be included when you save the run profile or review the run."
      : "Monitoring set loaded. Save monitoring panels to apply it to this run.";
  });
  const save = button("Save as new set", () => saveDialog(false));
  const update = button("Update saved set", () => saveDialog(true));
  const remove = button("Delete set", async () => {
    const saved = selected();
    if (
      !saved ||
      !window.confirm(
        `Delete saved monitoring set “${saved.definition.name}”? Existing runs and profiles keep their copies.`,
      )
    )
      return;
    await action(async () => {
      await api(
        `/execution/monitoring-sets/${encodeURIComponent(saved.id)}?revision=${saved.revision}&${scope}`,
        { method: "DELETE" },
      );
      if (disposed) return;
      sets = sets.filter((s) => s.id !== saved.id);
      render();
      status.textContent = "Saved set deleted. Current panels are unchanged.";
    });
  });
  const reload = button("Refresh saved sets", () => fetchSets());
  function controls() {
    load.disabled = update.disabled = remove.disabled = busy || !selected();
    save.disabled = reload.disabled = picker.disabled = busy;
  }
  function render(value = picker.value) {
    picker.replaceChildren(
      el("option", { value: "" }, "Choose a saved monitoring set"),
      ...sets
        .slice()
        .sort((a, b) => a.definition.name.localeCompare(b.definition.name))
        .map((s) => el("option", { value: s.id }, s.definition.name)),
    );
    picker.value = sets.some((s) => s.id === value) ? value : "";
    controls();
  }
  async function action(work) {
    if (busy || disposed) return;
    busy = true;
    error.hidden = true;
    controls();
    try {
      await work();
    } catch (reason) {
      if (!disposed) {
        error.textContent = reason.message;
        error.hidden = false;
        error.scrollIntoView({ block: "center" });
      }
    } finally {
      busy = false;
      if (!disposed) controls();
    }
  }
  async function fetchSets() {
    await action(async () => {
      const result = await api(`/execution/monitoring-sets?${scope}`);
      if (!disposed) {
        sets = result;
        render();
      }
    });
  }
  function saveDialog(overwrite) {
    const previous = overwrite ? selected() : null;
    if (overwrite && !previous) return;
    const name = input(previous?.definition.name || "");
    name.required = true;
    name.maxLength = 100;
    const message = el("p", { role: "alert", class: "error", hidden: true });
    const submit = el(
      "button",
      { type: "submit", class: "primary" },
      overwrite ? "Update set" : "Save set",
    );
    const form = el(
      "form",
      {},
      el("h2", {}, overwrite ? "Update saved monitoring set" : "Save monitoring set"),
      labeled("Set name", name),
      el(
        "p",
        { class: "muted" },
        "Saves the current panels for reuse in future runs. Only credential references are stored.",
      ),
      message,
      el(
        "div",
        { class: "dialog-actions" },
        button("Cancel", () => dialog.close()),
        submit,
      ),
    );
    dialog = el(
      "dialog",
      { class: "deployment-dialog", "aria-label": "Save monitoring set" },
      form,
    );
    const current = dialog;
    current.addEventListener("close", () => current.remove());
    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      submit.disabled = true;
      message.hidden = true;
      const panels = editor.read();
      // Live dashboards do not edit evaluation rules. Preserve rules for unchanged metric IDs when updating a set.
      const rules = evaluation
        ? editor.readThresholds()
        : (previous?.definition.thresholds || []).filter((r) =>
            panels.some((p) => p.name === r.metric && p.kind !== "logs"),
          );
      try {
        const result = await api(`/execution/monitoring-sets?${scope}`, {
          method: "POST",
          body: {
            id: previous?.id || null,
            revision: previous?.revision || null,
            definition: { name: name.value, panels, thresholds: rules },
          },
        });
        if (disposed) return;
        sets = sets.filter((s) => s.id !== result.id);
        sets.push(result);
        render(result.id);
        status.textContent = `Monitoring set “${result.definition.name}” saved for future runs.`;
        current.close();
      } catch (reason) {
        if (!disposed) {
          message.textContent = reason.message;
          message.hidden = false;
        }
      } finally {
        submit.disabled = false;
      }
    });
    document.body.append(current);
    current.showModal();
    name.focus();
  }
  picker.addEventListener("change", controls);
  const node = el(
    "div",
    { class: "spacer" },
    labeled("Saved monitoring sets", picker),
    el("div", { class: "card-actions" }, load, save, update, remove, reload),
    el(
      "p",
      { class: "muted" },
      "Save a named set to reuse panels across runs. Adding or editing a panel changes this form until you save a set or the run profile.",
    ),
    status,
    error,
  );
  controls();
  fetchSets();
  return {
    node,
    dispose: () => {
      disposed = true;
      dialog?.close();
      dialog?.remove();
    },
  };
}

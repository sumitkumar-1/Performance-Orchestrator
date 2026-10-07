import { el, labeled, input, select } from "./dom.js";

const actionLabel = (value) =>
  value === "VERIFY_EXISTING" ? "Verify existing" : "Deploy";
const button = (text, callback, className = "") =>
  el(
    "button",
    {
      type: "button",
      class: className,
      onclick: callback,
    },
    text,
  );

// The editor consumes catalog data from the API; it does not depend on how that catalog is sourced.
export function deploymentEditor({ catalog, api, environment, selections }) {
  const chosen = new Map(
    selections.map((value) => [value.serviceId, structuredClone(value)]),
  );
  let target = environment,
    activeDialog;
  const list = el("div", { class: "deployment-list", "aria-live": "polite" });
  const count = el("span", { class: "subtle" });
  const add = button("+ Add deployment", () => openDialog(), "primary");
  const root = el(
    "section",
    { class: "card spacer" },
    el(
      "div",
      { class: "card-head" },
      el(
        "div",
        {},
        el("h2", {}, "02  Deployments"),
        el(
          "p",
          { class: "muted" },
          "Deploys from top to bottom. Drag the handle or use the arrows to reorder services.",
        ),
      ),
      add,
    ),
    count,
    list,
  );

  const destination = (serviceId) =>
    catalog.services[serviceId]?.deploymentByEnvironment[target];
  const available = () =>
    Object.keys(catalog.services).filter(
      (id) => !chosen.has(id) && destination(id),
    );
  const describeTarget = (serviceId) => {
    const mapping = destination(serviceId);
    return mapping
      ? `${target} · namespace ${mapping.namespace} · release ${mapping.releaseName}`
      : `No deployment mapping for ${target}. Remove this service or select another environment.`;
  };
  let draggedService = null;
  function move(from, to) {
    const entries = [...chosen.entries()];
    if (from < 0 || to < 0 || from >= entries.length || to >= entries.length || from === to) return;
    const [entry] = entries.splice(from, 1); entries.splice(to, 0, entry);
    chosen.clear(); entries.forEach(([id, value]) => chosen.set(id, value));
    render(); list.children[to]?.querySelector('.service-drag-handle')?.focus();
  }
  function render() {
    count.textContent = `${chosen.size} ${chosen.size === 1 ? "service" : "services"} selected`;
    add.disabled = available().length === 0;
    add.title = add.disabled
      ? "All services mapped to this environment are already added"
      : "";
    list.replaceChildren();
    if (!chosen.size)
      list.append(
        el(
          "div",
          { class: "deployment-empty" },
          el("h3", {}, "No deployments added"),
          el(
            "p",
            { class: "muted" },
            "Choose a service from the catalog, then select its image source and version.",
          ),
        ),
      );
    for (const [index, value] of [...chosen.values()].entries()) {
      const handle = el('button', { type: 'button', class: 'service-drag-handle', draggable: 'true',
        'aria-label': `Reorder ${value.serviceId}, position ${index + 1}`,
        ondragstart: event => { draggedService = value.serviceId; event.dataTransfer.effectAllowed = 'move'; event.dataTransfer.setData('text/plain', value.serviceId); },
        ondragend: () => { draggedService = null; list.querySelectorAll('.service-drop-target').forEach(node => node.classList.remove('service-drop-target')); }
      }, '⠿');
      const up = button('↑', () => move(index, index - 1)); up.disabled = index === 0;
      up.setAttribute('aria-label', `Move ${value.serviceId} up`);
      const down = button('↓', () => move(index, index + 1)); down.disabled = index === chosen.size - 1;
      down.setAttribute('aria-label', `Move ${value.serviceId} down`);
      const edit = button("Edit", () => openDialog(value.serviceId));
      edit.setAttribute("aria-label", `Edit ${value.serviceId}`);
      const remove = button(
        "Remove",
        () => {
          chosen.delete(value.serviceId);
          render();
          add.focus();
        },
        "danger",
      );
      remove.setAttribute("aria-label", `Remove ${value.serviceId}`);
      list.append(
        el(
          "article",
          { class: "deployment-row", "aria-label": value.serviceId,
            ondragover: event => { if (draggedService && draggedService !== value.serviceId) { event.preventDefault(); event.dataTransfer.dropEffect = 'move'; event.currentTarget.classList.add('service-drop-target'); } },
            ondragleave: event => { if (!event.currentTarget.contains(event.relatedTarget)) event.currentTarget.classList.remove('service-drop-target'); },
            ondrop: event => { event.preventDefault(); event.currentTarget.classList.remove('service-drop-target'); if (draggedService) move([...chosen.keys()].indexOf(draggedService), index); draggedService = null; }
          },
          el(
            "div",
            { class: "deployment-summary" },
            el("h3", {}, `${index + 1}. ${value.serviceId}`),
            el(
              "div",
              { class: "service-chips" },
              el("span", { class: "chip" }, actionLabel(value.action)),
              el(
                "span",
                { class: "chip" },
                `${value.build.sourceRef}${value.build.username ? " / " + value.build.username : ""}`,
              ),
              el("span", { class: "chip" }, value.build.version),
            ),
            el(
              "p",
              {
                class: destination(value.serviceId)
                  ? "subtle destination"
                  : "error",
              },
              describeTarget(value.serviceId),
            ),
          ),
          el("div", { class: "deployment-actions" }, handle, up, down, edit, remove),
        ),
      );
    }
  }

  function openDialog(editingId) {
    if (activeDialog) return;
    const saved = chosen.get(editingId);
    const origin = document.activeElement;
    const dialog = el("dialog", {
      class: "deployment-dialog",
      "aria-labelledby": "deployment-dialog-title",
      "aria-describedby": "deployment-dialog-help",
    });
    activeDialog = dialog;
    const service = select(
      [
        ["", "Choose a service"],
        ...Object.keys(catalog.services)
          .filter(
            (id) => (id === editingId || !chosen.has(id)) && destination(id),
          )
          .map((id) => [id, id]),
      ],
      editingId || "",
    );
    service.disabled = !!editingId;
    if (!editingId) service.setAttribute("autofocus", "");
    const action = select(
      [
        ["DEPLOY", "Deploy selected image"],
        ["VERIFY_EXISTING", "Verify existing image"],
      ],
      saved?.action || "DEPLOY",
    );
    const source = select([["", "Choose an image source"]], "");
    const username = input(saved?.build.username || "");
    const usernameField = labeled("Artifact-owner username", username);
    usernameField.hidden = true;
    const search = input("", "search", {
      placeholder: "Filter discovered versions",
    });
    const version = select([["", "Choose a discovered version"]], "");
    const status = el(
      "p",
      { class: "discovery-status", role: "status" },
      "Choose a service to discover its simulated builds.",
    );
    const error = el("p", { class: "error", role: "alert", hidden: true });
    const mapping = el("div", { class: "destination-preview" });
    const overlay = el("textarea", {}, saved?.valuesOverlay || "");
    let images = [],
      controller,
      sequence = 0,
      selectedVersion = saved?.build.version || "";
    const commit = button(
      saved ? "Save deployment" : "Add deployment",
      () => {
        if (
          !service.value ||
          !source.value ||
          !images.some((image) => image.version === selectedVersion)
        ) {
          error.hidden = false;
          error.textContent =
            "Choose a service and an available image version.";
          return;
        }
        chosen.set(service.value, {
          serviceId: service.value,
          action: action.value,
          build: {
            sourceRef: source.value,
            username: usernameField.hidden ? "" : username.value,
            version: selectedVersion,
          },
          valuesOverlay: overlay.value,
        });
        dialog.close();
        render();
      },
      "primary",
    );
    commit.disabled = true;
    function filter() {
      const filtered = images.filter(
        (image) =>
          image.version.toLowerCase().includes(search.value.toLowerCase()) ||
          image.version === selectedVersion,
      );
      version.replaceChildren(
        el("option", { value: "" }, "Choose a discovered version"),
        ...filtered.map((image) =>
          el("option", { value: image.version }, image.version),
        ),
      );
      version.value = selectedVersion;
    }
    async function discover(preserve = "") {
      controller?.abort();
      controller = new AbortController();
      const request = ++sequence;
      images = [];
      selectedVersion = "";
      filter();
      commit.disabled = true;
      error.hidden = true;
      usernameField.hidden = !catalog.sources[source.value]?.usernameRequired;
      if (!service.value || !source.value) {
        status.textContent = "Choose a service and image source.";
        return;
      }
      if (!usernameField.hidden && !username.value.trim()) {
        status.textContent =
          "Enter the artifact-owner username to discover versions.";
        return;
      }
      status.textContent = "Discovering simulated builds…";
      try {
        const result = await api(
          `/services/${encodeURIComponent(service.value)}/images?source=${encodeURIComponent(source.value)}${usernameField.hidden ? "" : "&username=" + encodeURIComponent(username.value)}`,
          { signal: controller.signal },
        );
        if (request !== sequence || !dialog.open) return;
        images = result.items;
        selectedVersion = images.some((image) => image.version === preserve)
          ? preserve
          : "";
        filter();
        commit.disabled = !selectedVersion;
        status.textContent = images.length
          ? `${result.simulated ? "Simulated builds" : "Registry builds"} · ${images.length} versions available. Select a version to continue.`
          : "No versions found in this source.";
        if (preserve && !selectedVersion)
          status.textContent = `Saved version ${preserve} is unavailable. Choose another discovered version.`;
      } catch (failure) {
        if (request === sequence && failure.name !== "AbortError")
          status.textContent = failure.message;
      }
    }
    function setService(preserveSource = "", preserveVersion = "") {
      const mappings =
        catalog.services[service.value]?.installationBindings.sourceSelectors ||
        {};
      const sources = Object.entries(catalog.sources).filter(([id]) =>
        Object.hasOwn(mappings, id),
      );
      source.replaceChildren(
        el("option", { value: "" }, "Choose an image source"),
        ...sources.map(([id, data]) =>
          el("option", { value: id }, data.displayName),
        ),
      );
      source.value = sources.some(([id]) => id === preserveSource)
        ? preserveSource
        : sources[0]?.[0] || "";
      mapping.replaceChildren();
      if (service.value) {
        const config = destination(service.value);
        mapping.append(
          el("strong", {}, `Destination: ${target}`),
          el(
            "dl",
            { class: "mapping-list" },
            el("dt", {}, "Namespace"),
            el("dd", {}, config.namespace),
            el("dt", {}, "Release"),
            el("dd", {}, config.releaseName),
            el("dt", {}, "Values files"),
            el("dd", {}, config.valuesFiles.join(", ")),
          ),
        );
      }
      search.value = "";
      discover(preserveVersion);
    }
    const refresh = button("Refresh versions", () => discover(selectedVersion));
    service.addEventListener("change", () => {
      username.value = "";
      setService();
    });
    source.addEventListener("change", () => {
      search.value = "";
      discover();
    });
    username.addEventListener("input", () => discover());
    search.addEventListener("input", filter);
    version.addEventListener("change", () => {
      selectedVersion = version.value;
      commit.disabled = !selectedVersion;
    });
    dialog.append(
      el(
        "div",
        { class: "dialog-heading" },
        el(
          "h2",
          { id: "deployment-dialog-title" },
          saved ? "Edit deployment" : "Add deployment",
        ),
        button("Close", () => dialog.close()),
      ),
      el(
        "p",
        { id: "deployment-dialog-help", class: "muted" },
        "Service catalog and builds are simulated here. The target environment determines where this service runs; the image source determines which build it uses.",
      ),
      el(
        "div",
        { class: "form-grid" },
        labeled("Service", service),
        labeled("Action", action),
        labeled("Image source", source),
        usernameField,
      ),
      mapping,
      el(
        "div",
        { class: "version-search" },
        labeled("Search versions", search),
        refresh,
      ),
      labeled("Available version", version),
      status,
      el(
        "details",
        {},
        el("summary", {}, "Service values overlay (optional)"),
        labeled("Values overlay YAML", overlay),
      ),
      error,
      el(
        "div",
        { class: "dialog-actions" },
        button("Cancel", () => dialog.close()),
        commit,
      ),
    );
    dialog.addEventListener("close", () => {
      ++sequence;
      controller?.abort();
      dialog.remove();
      activeDialog = undefined;
      if (origin?.isConnected) origin.focus();
      else add.focus();
    });
    root.append(dialog);
    dialog.showModal();
    if (saved) setService(saved.build.sourceRef, saved.build.version);
  }
  render();
  return {
    element: root,
    selections: () => structuredClone([...chosen.values()]),
    setEnvironment(value) {
      activeDialog?.close();
      target = value;
      render();
    },
    dispose() {
      activeDialog?.close();
    },
  };
}

import { el, input, labeled, select } from "./dom.js";

// Forms edit a copy of the complete document; the server validates references and revision atomically.
export function configurationManager(active, { api, mode, onSaved, onSignIn, authStates }) {
  const real = mode === "real";
  const action = (text, fn) => el("button", { type: "button", onclick: fn }, text);
  const lines = value => (value || []).join("\n");
  function modal(title) {
    const body = el("div", { class: "config-form" });
    const status = el("p", { role: "alert", class: "error", hidden: true });
    const form = el("form", {}, el("div", { class: "dialog-heading" }, el("h2", {}, title)), body, status);
    const dialog = el("dialog", { class: "deployment-dialog config-dialog", "aria-label": title }, form);
    const cancel = action("Cancel", () => dialog.close());
    const save = el("button", { type: "submit", class: "primary" }, "Save changes");
    form.append(el("div", { class: "dialog-actions" }, cancel, save));
    dialog.addEventListener("cancel", event => { if (save.disabled) event.preventDefault(); });
    dialog.addEventListener("close", () => dialog.remove());
    document.body.append(dialog);
    return { dialog, form, body, status, save, cancel };
  }
  async function persist(draft, ui) {
    if (ui.save.disabled) return;
    ui.save.disabled = true;
    ui.cancel.disabled = true;
    try { await api("/configuration", { method: "PUT", body: draft }); ui.dialog.close(); await onSaved(); }
    catch (error) { ui.status.hidden = false; ui.status.textContent = error.message + " Changes have not been applied. If another editor saved changes, close this dialog and reload the page."; }
    finally { ui.save.disabled = false; ui.cancel.disabled = false; }
  }
  function edit(group, key, id, entry) {
    const ui = modal(`${id ? "Edit" : "Add"} ${titles[key]}`);
    const value = structuredClone(entry || {});
    const readers = [];
    const field = (label, initial = "", options = {}) => {
      const node = options.choices ? select(options.choices.map(v => [v, v || "None"]), initial ?? "")
        : options.multiline ? el("textarea", { rows: 3 }, initial ?? "") : input(initial ?? "");
      node.required = !!options.required;
      node.disabled = !!options.disabled;
      ui.body.append(labeled(label, node));
      return node;
    };
    const refs = kind => ["", ...Object.keys(active.connections[kind])];
    const bind = (label, key, options = {}, object = value) => {
      const node = field(label, options.multiline ? lines(object[key]) : object[key], options);
      readers.push(() => { object[key] = options.multiline ? node.value.split("\n").map(s => s.trim()).filter(Boolean) : node.value.trim() || null; });
      return node;
    };
    const note = text => ui.body.append(el("p", { class: "muted" }, text));
    const idField = field("Configuration ID", id || "", { required: true, disabled: !!id });
    idField.pattern = "[a-zA-Z0-9_-]{1,80}";
    function mapping(title, original, columns) {
      const rows = el("div", { class: "mapping-rows" });
      const controls = [];
      const add = (key = "", entry = {}) => {
        const row = el("div", { class: "mapping-row" });
        const inputs = [input(key, "text", { required: true }), ...columns.map(column => column.choices
          ? select(column.choices.map(v => [v, v || "None"]), entry[column.key] || "") : input(entry[column.key] || "", "text", { required: true }))];
        inputs.forEach((node, index) => row.append(labeled(index ? columns[index - 1].label : "Name", node)));
        row.append(action("Remove row", () => { row.remove(); controls.splice(controls.indexOf(inputs), 1); }));
        controls.push(inputs); rows.append(row);
      };
      Object.entries(original || {}).forEach(([key, value]) => add(key, value));
      ui.body.append(el("fieldset", {}, el("legend", {}, title), rows, action("Add row", () => add())));
      return () => {
        const result = {};
        for (const nodes of controls) {
          const name = nodes[0].value.trim();
          if (!name || Object.hasOwn(result, name)) throw new Error(`${title}: names must be unique and non-empty.`);
          Object.defineProperty(result, name, { enumerable: true, configurable: true, writable: true,
            value: Object.fromEntries(columns.map((col, index) => [col.key, nodes[index + 1].value.trim() || null])) });
        }
        return result;
      };
    }
    if (key === "environments") {
      bind("Display name", "displayName", { required: true });
      bind("Cluster identity", "clusterIdentity", { required: true, disabled: real });
      if (real) note("This instance's environment and cluster are fixed by startup configuration. Monitoring settings can be changed here.");
      value.monitoring ||= {};
      bind("Loki logs API URL", "logsApiBaseUrl", {}, value.monitoring);
      bind("Metrics API URL", "metricsApiBaseUrl", {}, value.monitoring);
      const read = mapping("Namespace credentials", value.monitoring.namespaceCredentials, [
        { key: "logsCredentialRef", label: "Logs credential", choices: refs("credentials") },
        { key: "metricsCredentialRef", label: "Metrics credential", choices: refs("credentials") }]);
      readers.push(() => { value.monitoring.namespaceCredentials = read(); });
      if (!real) note("Simulator limits and action permissions remain available in Advanced JSON.");
    } else if (key === "artifactory") {
      bind("Docker API base URL", "apiBaseUrl", { required: true });
      note("Example: https://artifactory.domain/artifactory/api/docker. Repository and image paths are configured under Image repositories.");
      bind("Credential reference", "credentialRef", { required: true, choices: refs("credentials") });
    } else if (key === "secretServers") {
      bind("Secret Server API base URL", "apiBaseUrl", { required: true });
      note("Use https://domain/SecretServer/api/v1. The client appends /secrets/{secretId}.");
      value.authMode ||= "portal";
      const mode = bind("Authentication", "authMode", { choices: ["portal", "environment", "file"] });
      const token = bind("OAuth token URL", "tokenUrl");
      const env = bind("Token environment variable name", "bearerTokenEnvironmentVariable");
      const file = bind("Absolute token file path", "bearerTokenFile");
      const toggle = () => [[token, "portal"], [env, "environment"], [file, "file"]].forEach(([node, match]) => { node.parentElement.hidden = mode.value !== match; node.required = mode.value === match; });
      mode.addEventListener("change", toggle); toggle();
      readers.push(() => { if (value.authMode !== "portal") value.tokenUrl = null; if (value.authMode !== "environment") value.bearerTokenEnvironmentVariable = null; if (value.authMode !== "file") value.bearerTokenFile = null; });
      note("Portal authentication uses the Sign in dialog. Store token references here, never token values.");
    } else if (key === "credentials") {
      value.provider ||= "delinea";
      const provider = bind("Provider", "provider", { choices: ["delinea", "environment"] });
      const vault = bind("Secret Server", "secretServerRef", { choices: refs("secretServers") });
      const secret = bind("Secret ID", "secretId");
      value.usernameFieldSlug ??= "username"; value.passwordFieldSlug ??= "password";
      const userSlug = bind("Username field slug", "usernameFieldSlug");
      const passSlug = bind("Password field slug", "passwordFieldSlug");
      const userEnv = bind("Username environment variable name", "usernameEnvironmentVariable");
      const passEnv = bind("Password environment variable name", "passwordEnvironmentVariable");
      const toggle = () => [[vault, "delinea"], [secret, "delinea"], [userSlug, "delinea"], [passSlug, "delinea"], [userEnv, "environment"], [passEnv, "environment"]].forEach(([node, match]) => { node.parentElement.hidden = provider.value !== match; node.required = provider.value === match; });
      provider.addEventListener("change", toggle); toggle();
      readers.push(() => { for (const key of value.provider === "delinea" ? ["usernameEnvironmentVariable", "passwordEnvironmentVariable"] : ["secretServerRef", "secretId", "usernameFieldSlug", "passwordFieldSlug"]) value[key] = null; });
    } else if (key === "imageSources") {
      bind("Display name", "displayName", { required: true });
      bind("Artifactory connection", "connectionRef", { required: true, choices: refs("artifactory") });
      bind("Docker repository key", "repositoryKey", { required: true });
      bind("Image pull repository template", "pullRepositoryTemplate", { required: true });
      note("Example: artifactory.domain/{repositoryKey}/{image}. Tags use the selected connection's /{repositoryKey}/v2/{image}/tags/list API.");
      const owner = field("Image paths require an artifact-owner username", String(value.usernameRequired || false), { choices: ["false", "true"] });
      const read = mapping("Service → image path", Object.fromEntries(Object.entries(value.imagePaths || {}).map(([k, v]) => [k, { path: v }])), [{ key: "path", label: "Image path (optional {username})" }]);
      readers.push(() => { value.usernameRequired = owner.value === "true"; value.imagePaths = Object.fromEntries(Object.entries(read()).map(([k, v]) => [k, v.path])); });
    } else if (key === "services") {
      bind("Project path", "projectPath", { required: true });
      note("Relative project path. Remote Git checkout and Helm execution are not connected in real mode yet.");
      bind("Dependencies (one service ID per line)", "dependencies", { multiline: true });
      bind("Allowed values override paths (one per line)", "allowedOverridePaths", { multiline: true });
      value.deploymentByEnvironment ||= {};
      const targets = real ? Object.keys(active.catalog.environments) : Object.keys(value.deploymentByEnvironment);
      if (real) {
        const target = targets[0];
        const destination = value.deploymentByEnvironment[target] || (value.deploymentDefaults ||= { valuesFiles: [] });
        note(`Deployment destination for ${target}`);
        bind("Namespace", "namespace", { required: true }, destination);
        bind("Helm release name", "releaseName", { required: true }, destination);
        bind("Values files (one relative path per line)", "valuesFiles", { required: true, multiline: true }, destination);
      } else for (const target of targets) {
        note(`Destination: ${target}`);
        const destination = value.deploymentByEnvironment[target];
        bind("Namespace", "namespace", { required: true }, destination);
        bind("Helm release name", "releaseName", { required: true }, destination);
        bind("Values files", "valuesFiles", { required: true, multiline: true }, destination);
      }
    }
    ui.form.addEventListener("submit", async event => {
      event.preventDefault();
      try {
        readers.forEach(read => read());
        const name = idField.value.trim();
        if (!id && Object.hasOwn(active[group][key], name)) throw new Error("This ID already exists. Choose another ID.");
        const draft = structuredClone(active);
        Object.defineProperty(draft[group][key], name, { value, enumerable: true, configurable: true, writable: true });
        await persist(draft, ui);
      } catch (error) { ui.status.hidden = false; ui.status.textContent = error.message; }
    });
    ui.dialog.showModal();
  }
  function remove(group, key, id) {
    const ui = modal(`Delete ${id}?`);
    ui.body.append(el("p", {}, "This removes the entry from runtime configuration. Entries still referenced by other settings cannot be deleted; update their references first."));
    ui.save.textContent = "Delete entry";
    ui.form.addEventListener("submit", event => { event.preventDefault(); const draft = structuredClone(active); delete draft[group][key][id]; persist(draft, ui); });
    ui.dialog.showModal();
  }
  const titles = { environments: "environment", artifactory: "Artifactory connection", secretServers: "Secret Server", credentials: "credential reference", imageSources: "image repository", services: "service project" };
  const sections = [
    ["catalog", "environments", "Environment", "Monitoring URLs and namespace credentials for this instance."],
    ["connections", "secretServers", "Secret Servers", "Vault endpoints and authentication. Use Sign in to start an AD session."],
    ["connections", "credentials", "Credential references", "Secret IDs and field names, shared by registry and monitoring connections."],
    ["connections", "artifactory", "Artifactory connections", "Registry server URLs and their credential references."],
    ["connections", "imageSources", "Image repositories", "Repository keys and service image paths on an Artifactory connection."],
    ["catalog", "services", "Service projects", "Project paths, namespaces, Helm releases and values files."],
  ];
  const root = el("div", { class: "configuration-sections" });
  sections.forEach(([group, key, title, description]) => {
    const locked = key === "environments";
    const canAdd = !locked && (real || key !== "services");
    const entries = Object.entries(active[group][key]);
    const section = el("details", { class: "card config-section", open: key === "environments" },
      el("summary", {}, title, el("span", { class: "config-count" }, String(entries.length))));
    const head = el("div", { class: "config-section-head" }, el("p", { class: "muted" }, description));
    if (canAdd) head.append(action(`Add ${titles[key]}`, () => edit(group, key)));
    section.append(head);
    if (!entries.length) section.append(el("p", { class: "muted" }, "No entries configured."));
    entries.forEach(([id, entry]) => {
      let description = entry.apiBaseUrl || entry.displayName || entry.projectPath || "";
      if (key === "credentials") description = entry.provider === "delinea" ? `${entry.secretServerRef} · Secret ${entry.secretId}` : "Environment variable references";
      if (key === "artifactory") description += ` · Credential: ${entry.credentialRef}`;
      if (key === "imageSources") description = `${entry.connectionRef} → ${entry.repositoryKey} · ${Object.keys(entry.imagePaths).length} image mappings`;
      const controls = el("div", { class: "config-row-actions" }, action("Edit", () => edit(group, key, id, entry)));
      if (!locked) controls.append(action("Delete", () => remove(group, key, id)));
      const text = el("div", {}, el("strong", {}, id), el("p", { class: "muted" }, description));
      if (key === "secretServers") {
        const state = authStates[id];
        text.append(el("small", {}, state?.state === "AUTHENTICATED" ? `Signed in until ${new Date(state.expiresAt).toLocaleString()}` : `${entry.authMode || "environment"} authentication`));
        if (state?.mode === "portal") controls.prepend(action(state.state === "AUTHENTICATED" ? "Sign out" : "Sign in", async () => {
          if (state.state !== "AUTHENTICATED") return onSignIn(id);
          const ui = modal("Sign out of Secret Server?");
          ui.body.append(el("p", {}, "The current vault session will be cleared.")); ui.save.textContent = "Sign out";
          ui.form.addEventListener("submit", async event => { event.preventDefault(); try { await api(`/secret-auth/${encodeURIComponent(id)}`, { method: "DELETE" }); ui.dialog.close(); await onSaved(); } catch (error) { ui.status.hidden = false; ui.status.textContent = error.message; } }); ui.dialog.showModal();
        }));
      }
      section.append(el("div", { class: "config-row" }, text, controls));
    });
    root.append(section);
  });
  const scenarios = el("details", { class: "card config-section" }, el("summary", {}, "Scenario templates", el("span", { class: "config-count" }, String(Object.keys(active.catalog.scenarios).length))),
    el("p", { class: "muted" }, "Read-only here. Templates come from startup configuration or JSON import. Creating real load profiles from the overview is not available yet."));
  Object.entries(active.catalog.scenarios).forEach(([id, scenario]) => scenarios.append(el("div", { class: "config-row" }, el("div", {}, el("strong", {}, scenario.displayName || id), el("p", { class: "muted" }, `${id} · ${scenario.revision}`), el("details", {}, el("summary", {}, "View template"), el("pre", {}, JSON.stringify(scenario.defaults, null, 2)))))));
  root.append(scenarios);
  return root;
}

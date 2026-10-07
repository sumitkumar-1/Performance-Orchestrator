import { el, input, labeled, select } from "./dom.js";

// Forms edit a copy of the complete document; the server validates references and revision atomically.
export function configurationManager(active, { api, mode, onSaved, onSignIn, onConnectionSession, authStates }) {
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
      const node = options.choices ? select(options.choices.map(v => Array.isArray(v) ? v : [v, v || "None"]), initial ?? "")
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
      bind("Shared Loki connection", "connectionRef", { choices: refs("loki") }, value.monitoring);
      note("Logs and LogQL metric queries use this shared connection. Namespace and per-environment credentials belong to Services.");
      if (value.monitoring.metricsApiBaseUrl || value.monitoring.namespaceCredentials)
        note("Some legacy monitoring fields were preserved in Advanced JSON to avoid losing distinct credentials or unmatched namespaces. Review them before removing them.");
      if (!real) note("Simulator limits and action permissions remain available in Advanced JSON.");
    } else if (key === "loki") {
      bind("Loki API base URL", "apiBaseUrl", { required: true });
      note("Example: https://logs.dev-domain/loki/api/v1. Multiple environments can reference this endpoint. Credentials are selected per service/environment.");
    } else if (key === "artifactory" || key === "bitbucket") {
      bind(key === "artifactory" ? "Docker API base URL" : "Bitbucket REST API base URL", "apiBaseUrl", { required: true });
      note(key === "artifactory" ? "Example: https://artifactory.domain/artifactory/api/docker. Service settings supply docker-{stage} and {teamId}/{imageName}." : "Example: https://stash.domain.net/rest/api. Service settings identify the project, repository, Git revision and Helm chart.");
      value.authMode ||= "token";
      const mode = bind("Authentication", "authMode", { choices: [["token", "Bearer token session"], ["secret-server", "Resolve token reference"]] });
      const credential = bind("Credential reference", "credentialRef", { choices: refs("credentials") });
      const toggle = () => { credential.parentElement.hidden = mode.value !== "secret-server"; credential.required = mode.value === "secret-server"; };
      mode.addEventListener("change", toggle); toggle();
      readers.push(() => { if (value.authMode !== "secret-server") value.credentialRef = null; });
      note("Use Session to sign in once for this connection. Tokens stay encrypted in server memory with a fixed expiry and are never written to configuration. Referenced credentials must contain a token.");
    } else if (key === "secretServers") {
      bind("Secret Server API base URL", "apiBaseUrl", { required: true });
      note("Use https://domain/SecretServer/api/v1. The client appends /secrets/{secretId}.");
      const authMode = bind("Authentication", "authMode", { choices: [["interactive", "AD sign-in or access token"], ["token", "Access token only"]] });
      const tokenUrl = bind("OAuth token URL", "tokenUrl");
      const updateAuth = () => { tokenUrl.parentElement.hidden = authMode.value !== "interactive"; tokenUrl.required = authMode.value === "interactive"; };
      authMode.addEventListener("change", updateAuth); updateAuth();
      note("AD credentials are exchanged for a token and never saved. The returned expiry is tracked automatically. Token-only sessions do not verify a username.");
      readers.push(() => { if(value.authMode !== "interactive") value.tokenUrl = null; value.credentialRef = null; value.bearerTokenEnvironmentVariable = null; value.bearerTokenFile = null; });
    } else if (key === "credentials") {
      value.provider ||= "delinea";
      const provider = bind("Provider", "provider", { choices: ["delinea", "environment"] });
      const kind = field("Credential content", value.tokenFieldSlug || value.tokenEnvironmentVariable ? "token" : "basic", { choices: [["basic", "Username and password"], ["token", "Bearer token"]] });
      const vault = bind("Secret Server", "secretServerRef", { choices: refs("secretServers") });
      const secret = bind("Secret ID", "secretId");
      value.usernameFieldSlug ??= "username"; value.passwordFieldSlug ??= "password";
      const userSlug = bind("Username field slug", "usernameFieldSlug");
      const passSlug = bind("Password field slug", "passwordFieldSlug");
      const tokenSlug = bind("Token field slug", "tokenFieldSlug");
      const userEnv = bind("Username environment variable name", "usernameEnvironmentVariable");
      const passEnv = bind("Password environment variable name", "passwordEnvironmentVariable");
      const tokenEnv = bind("Token environment variable name", "tokenEnvironmentVariable");
      const toggle = () => {
        const vaultMode = provider.value === "delinea", tokenMode = kind.value === "token";
        [[vault, vaultMode], [secret, vaultMode], [userSlug, vaultMode && !tokenMode], [passSlug, vaultMode && !tokenMode], [tokenSlug, vaultMode && tokenMode], [userEnv, !vaultMode && !tokenMode], [passEnv, !vaultMode && !tokenMode], [tokenEnv, !vaultMode && tokenMode]].forEach(([node, visible]) => { node.parentElement.hidden = !visible; node.required = visible; });
      };
      provider.addEventListener("change", toggle); kind.addEventListener("change", toggle); toggle();
      readers.push(() => {
        for (const [node, key] of [[vault, "secretServerRef"], [secret, "secretId"], [userSlug, "usernameFieldSlug"], [passSlug, "passwordFieldSlug"], [tokenSlug, "tokenFieldSlug"], [userEnv, "usernameEnvironmentVariable"], [passEnv, "passwordEnvironmentVariable"], [tokenEnv, "tokenEnvironmentVariable"]]) if (node.parentElement.hidden) value[key] = null;
      });
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
      note("Relative checkout destination. Bitbucket reference discovery is available; Git checkout and Helm execution are not yet connected.");
      if (real) {
        const containerImage = value.containerImage ||= {};
        note("Container image · Artifactory Docker tags");
        const imageConnection = bind("Artifactory connection (optional)", "connectionRef", { choices: refs("artifactory") }, value.containerImage);
        const stage = bind("Repository stage (dev, stable, etc.)", "repoStage", {}, value.containerImage);
        const team = bind("Team ID", "teamId", {}, value.containerImage);
        const image = bind("Docker image name", "imageName", {}, value.containerImage);
        note("Tags API: {Artifactory API base}/docker-{stage}/v2/{teamId}/{imageName}/tags/list");
        const sourceProject = value.sourceProject ||= {};
        note("Source repository · Bitbucket / Stash");
        const gitConnection = bind("Bitbucket connection (optional)", "connectionRef", { choices: refs("bitbucket") }, value.sourceProject);
        const project = bind("Bitbucket project key", "projectKey", {}, value.sourceProject);
        const repository = bind("Bitbucket repository slug", "repository", {}, value.sourceProject);
        const revision = bind("Git tag, branch or commit", "revision", {}, value.sourceProject);
        const chart = bind("Helm chart path within repository", "chartPath", {}, value.sourceProject);
        bind("HTTPS clone URL override (optional; discovered from Bitbucket by default)", "cloneUrl", {}, value.sourceProject);
        const toggle = () => {
          for (const [connection, fields] of [[imageConnection, [stage, team, image]], [gitConnection, [project, repository, revision, chart]]])
            fields.forEach(node => { node.parentElement.hidden = !connection.value; node.required = !!connection.value; });
        };
        imageConnection.addEventListener("change", toggle); gitConnection.addEventListener("change", toggle); toggle();
        readers.push(() => { value.containerImage = imageConnection.value ? containerImage : null; value.sourceProject = gitConnection.value ? sourceProject : null; });
      }
      if (real) {
        const credentials = value.monitoringCredentials ||= {};
        note("Loki monitoring · Uses the deployment namespace below for both logs and LogQL metrics.");
        for (const environment of Object.keys(active.catalog.environments)) {
          const credential = field(`Monitoring credential for ${environment}`, credentials[environment] || "", { choices: refs("credentials") });
          readers.push(() => { if (credential.value) credentials[environment] = credential.value; else delete credentials[environment]; });
        }
      }
      if (real) value.dependencies = [];
      else {
        bind("Deploy these services first (one service ID per line)", "dependencies", { multiline: true });
        note("Dependencies determine deployment order. Include those services in the run; leave empty when there is no ordering requirement.");
      }
      if (mode === "simulation") bind("Allowed values override paths (simulation only, one per line)", "allowedOverridePaths", { multiline: true });
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
  const titles = { environments: "environment", artifactory: "Artifactory connection", bitbucket: "Bitbucket connection", secretServers: "Secret Server", credentials: "credential reference", imageSources: "image repository", services: "service", loki: "Loki connection" };
  const sections = [
    ["catalog", "environments", "Environment", "Cluster identity and shared Loki connection for this instance."],
    ["connections", "secretServers", "Secret Servers", "Vault endpoints and authentication. Use Sign in to supply a Bearer access token."],
    ["connections", "credentials", "Credential references", "Secret IDs and field names, shared by registry and monitoring connections."],
    ["connections", "loki", "Loki connections", "Shared endpoints for logs and LogQL metrics. Referenced by environments."],
    ["connections", "artifactory", "Artifactory connections", "Registry server URLs and their credential references."],
    ["connections", "bitbucket", "Bitbucket connections", "Source-control servers with AD, token or Secret Server credentials."],
    ...(Object.keys(active.connections.imageSources).length ? [["connections", "imageSources", "Legacy image mappings", "Existing mappings remain usable. Configure new image mappings inside Service projects."]] : []),
    ["catalog", "services", "Services", "Project paths, namespaces, Helm releases and values files."],
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
      if (["artifactory", "bitbucket"].includes(key)) description += ` · ${entry.authMode || "secret-server"}${entry.credentialRef ? " · " + entry.credentialRef : ""}`;
      if (key === "imageSources") description = `${entry.connectionRef} → ${entry.repositoryKey} · ${Object.keys(entry.imagePaths).length} image mappings`;
      const controls = el("div", { class: "config-row-actions" }, action("Edit", () => edit(group, key, id, entry)));
      if (["artifactory", "bitbucket"].includes(key) && entry.authMode === "token") controls.prepend(action("Session", () => onConnectionSession(key, id)));
      if (!locked) controls.append(action("Delete", () => remove(group, key, id)));
      const text = el("div", {}, el("strong", {}, id), el("p", { class: "muted" }, description));
      if (key === "secretServers") {
        const state = authStates[id];
        const expires = state?.expiresAt ? new Date(state.expiresAt).toLocaleString() : "";
        const authenticationStatus = state?.state === "AUTHENTICATED"
          ? `AD authenticated as ${state.username} · token expires ${expires}. Secret permissions are checked when used.`
          : state?.state === "TOKEN_PROVIDED"
            ? `Token stored until ${expires} · not verified at sign-in`
            : `${entry.authMode || "environment"} authentication`;
        text.append(el("small", {}, authenticationStatus));
        if (["token", "interactive"].includes(state?.mode)) controls.prepend(action(["AUTHENTICATED", "TOKEN_PROVIDED"].includes(state.state) ? "Sign out" : "Sign in", async () => {
          if (!["AUTHENTICATED", "TOKEN_PROVIDED"].includes(state.state)) return onSignIn(id);
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
  const humanize = key => key.replace(/([a-z])([A-Z])/g, "$1 $2").replaceAll("_", " ").replace(/^./, char => char.toUpperCase());
  const renderValue = value => {
    if (Array.isArray(value)) return value.length ? el("ul", {}, value.map(item => el("li", {}, renderValue(item)))) : el("span", { class: "muted" }, "None");
    if (value && typeof value === "object") return el("dl", { class: "scenario-properties" }, Object.entries(value).flatMap(([key, child]) => [el("dt", {}, humanize(key)), el("dd", {}, renderValue(child))]));
    return el("span", {}, value === null || value === undefined ? "Not set" : typeof value === "boolean" ? value ? "Yes" : "No" : String(value));
  };
  if (!Object.keys(active.catalog.scenarios).length) scenarios.append(el("p", { class: "muted" }, "No scenario templates configured."));
  Object.entries(active.catalog.scenarios).forEach(([id, scenario]) => scenarios.append(
    el("article", { class: "scenario-template" }, el("h3", {}, scenario.displayName || id),
      el("p", { class: "muted" }, `${id} · Revision ${scenario.revision}`), renderValue(scenario.defaults),
      el("p", { class: "muted" }, scenario.allowedOverridePaths?.length ? `Editable fields: ${scenario.allowedOverridePaths.join(", ")}` : "No template override fields configured."))));
  root.append(scenarios);
  return root;
}

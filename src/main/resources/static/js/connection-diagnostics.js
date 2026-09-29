import { el, input, labeled, select } from "./dom.js";
import { operationAuthentication } from "./operation-auth.js";

// One selected service and operation at a time; results never carry across selections.
export function connectionDiagnostics(catalog, connections, api) {
  const sources = Object.entries(connections.imageSources || {});
  const serviceIds = [...new Set([
    ...Object.keys(catalog.services),
    ...sources.flatMap(([, source]) => Object.keys(source.imagePaths)),
  ])].sort();
  const node = el("div", { class: "spacer" });
  if (!serviceIds.length) {
    node.append(el("p", {}, "Configure a service and its Artifactory or Bitbucket connection to run diagnostics."));
    return { node, dispose() {} };
  }
  const service = select(serviceIds.map(id => [id, id]), serviceIds[0]);
  const operation = select([], "");
  const source = select([], "");
  const sourceField = labeled("Image source", source);
  const owner = input("");
  const ownerField = labeled("Artifact-owner username", owner);
  const versions = select([["", "Choose a discovered image version"]], "");
  const versionField = labeled("Image version", versions);
  const connectionInfo = el("p", { class: "muted" });
  const authentication = el("div");
  const output = el("pre", { role: "status" }, "No request made yet.");
  const fetch = el("button", { type: "button" }, "Fetch");
  const more = el("button", { type: "button", disabled: true }, "Next page");
  const digest = el("button", { type: "button", disabled: true }, "Resolve digest");
  let auth, next = null, busy = false, disposed = false, revision = 0;

  function imageSources() {
    return sources.filter(([, entry]) => Object.hasOwn(entry.imagePaths, service.value));
  }
  function resetResults() {
    next = null;
    versions.replaceChildren(el("option", { value: "" }, "Choose a discovered image version"));
    output.textContent = "No request made yet.";
    updateButtons();
  }
  function updateButtons() {
    service.disabled = operation.disabled = source.disabled = owner.disabled = busy;
    fetch.disabled = busy || !auth;
    more.disabled = busy || !auth || next === null;
    digest.disabled = busy || !auth || !versions.value;
  }
  function configureOperation() {
    revision++;
    auth?.dispose();
    auth = null;
    authentication.replaceChildren();
    const image = operation.value === "images";
    const entry = sources.find(([id]) => id === source.value)?.[1];
    const project = catalog.services[service.value]?.sourceProject;
    const kind = image ? "artifactory" : "bitbucket";
    const id = image ? entry?.connectionRef : project?.connectionRef;
    const connection = connections[kind]?.[id];
    sourceField.hidden = !image || imageSources().length <= 1;
    ownerField.hidden = !image || !entry?.usernameRequired;
    versionField.hidden = digest.hidden = !image;
    fetch.textContent = image ? "Fetch image versions" : operation.value === "branches" ? "Fetch Git branches" : "Fetch Git tags";
    connectionInfo.textContent = !operation.value ? "No image source or Git project configured for this service."
      : connection ? `${image ? "Artifactory" : "Bitbucket"}: ${id} · ${image ? entry.repositoryKey : project.projectKey + "/" + project.repository}`
      : "The selected connection is not configured.";
    if (operation.value && connection) {
      auth = operationAuthentication(connection, { api, kind, id });
      authentication.append(auth.node);
    }
    resetResults();
  }
  function configureService() {
    const entries = imageSources();
    source.replaceChildren(...entries.map(([id, entry]) => el("option", { value: id }, entry.displayName || id)));
    const operations = [];
    if (entries.length) operations.push(["images", "Container image versions"]);
    if (catalog.services[service.value]?.sourceProject) operations.push(["branches", "Git branches"], ["tags", "Git tags"]);
    operation.replaceChildren(...operations.map(([id, label]) => el("option", { value: id }, label)));
    owner.value = "";
    configureOperation();
  }
  async function request(action) {
    if (busy || !auth) return;
    const currentRevision = revision;
    busy = true; updateButtons();
    output.textContent = "Request in progress…";
    try {
      const image = operation.value === "images";
      const entry = sources.find(([id]) => id === source.value)?.[1];
      const endpoint = image
        ? `/registry-sources/${encodeURIComponent(source.value)}/services/${encodeURIComponent(service.value)}/images/query`
        : `/service-projects/${encodeURIComponent(service.value)}/references/query`;
      const body = image
        ? { username: entry.usernameRequired ? owner.value : null,
            ...(action === "digest" ? { tag: versions.value } : { limit: 50, cursor: action === "more" ? next : "" }) }
        : { kind: operation.value, start: action === "more" ? next : 0 };
      const result = await auth.run(credentials => api(endpoint, {
        method: "POST", body: { ...body, authentication: credentials },
      }));
      if (disposed || revision !== currentRevision) return;
      if (action !== "digest") {
        next = image ? result.nextCursor || null : result.nextStart ?? null;
        if (image) {
          if (action !== "more") versions.replaceChildren(el("option", { value: "" }, "Choose a discovered image version"));
          for (const tag of result.versions) versions.append(el("option", { value: tag }, tag));
        }
      }
      output.textContent = JSON.stringify(result, null, 2);
    } catch (error) {
      if (!disposed && revision === currentRevision) output.textContent = error.message;
    } finally {
      busy = false;
      if (!disposed) updateButtons();
    }
  }
  service.addEventListener("change", configureService);
  operation.addEventListener("change", configureOperation);
  source.addEventListener("change", configureOperation);
  owner.addEventListener("input", resetResults);
  versions.addEventListener("change", updateButtons);
  fetch.addEventListener("click", () => request("fetch"));
  more.addEventListener("click", () => request("more"));
  digest.addEventListener("click", () => request("digest"));
  node.append(el("div", { class: "form-grid" }, labeled("Service", service), labeled("Operation", operation),
    sourceField, ownerField, versionField), connectionInfo, authentication,
    el("div", { class: "card-actions" }, fetch, more, digest), output);
  configureService();
  return { node, dispose() { disposed = true; revision++; auth?.dispose(); } };
}

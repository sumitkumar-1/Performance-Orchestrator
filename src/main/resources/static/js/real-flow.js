import { monitoringConfig } from "./monitoring-config.js";
import { diagnosticsPanel } from "./diagnostics.js";
import { sortImageVersions } from "./image-versions.js";
import { el, input, labeled, select } from "./dom.js";

export async function realFlow(api, catalog, navigate) {
  const settings = await api("/real/execution");
  const root = el("div", { class: "run-builder" });
  let disposed = false, activeDialog;
  root.dispose = () => { disposed = true; activeDialog?.close(); activeDialog?.remove(); };
  if (!settings.enabled) {
    root.append(el("p", { class: "card" }, "Configure orchestrator.execution.enabled, kube-context and expected-api-server, then restart to enable deployment."));
    return root;
  }
  const ids = Object.keys(catalog.services).filter(id => catalog.services[id].sourceProject && catalog.services[id].containerImage).sort();
  if (!ids.length) { root.append(el("p", { class: "card" }, "Add service Git, image and Helm settings in Connections & catalog first.")); return root; }
  const name = input("Performance test"), duration = input("60", "number", { min: 1 });
  const warmup = input("0", "number", { min: 0 }), deadline = input("900", "number", { min: 60, max: 28800 });
  const rows = el("div", { class: "run-selections" }), loadSummary = el("div"), preview = el("section");
  const status = el("p", { role: "status", class: "run-status", "aria-live": "polite", hidden: true }), error = el("p", { role: "alert", class: "error run-error", tabindex: "-1", hidden: true });
  const notices = el("div", {class:"run-notices",hidden:true},error,status);
  const errorObserver = new MutationObserver(() => {
    error.hidden = !error.textContent; status.hidden = !status.textContent || !!error.textContent;
    notices.hidden = error.hidden && status.hidden;
    if(!notices.hidden)notices.scrollIntoView({block:"nearest",behavior:"smooth"});
    if(error.textContent)error.focus({preventScroll:true});
  });
  errorObserver.observe(error, { childList: true, characterData: true, subtree: true });
  errorObserver.observe(status, { childList: true, characterData: true, subtree: true });
  const disposeBase=root.dispose; root.dispose=()=>{errorObserver.disconnect();disposeBase();};
  const diagnostics=diagnosticsPanel(api);

  const disposeDiagnostics=root.dispose;root.dispose=()=>{diagnostics.dispose();disposeDiagnostics();};
  let selections = [], load = null, plan = null, savedId = null, savedRevision = null, working = false;
  const invalidate = () => { plan = null; preview.replaceChildren(); status.textContent=""; };
  const connections = await api("/connections");
  const monitoring = monitoringConfig(catalog, connections.credentialReferences, invalidate, { evaluation: true, api });
  const disposeCurrent=root.dispose;root.dispose=()=>{monitoring.dispose();disposeCurrent();};
  const destination = id => catalog.services[id]?.deploymentByEnvironment?.[catalog.environment] || catalog.services[id]?.deploymentDefaults;
  const button = (text, action, primary = false) => el("button", { type: "button", class: primary ? "primary" : "", onclick: action }, text);

  let draggedService = null;
  function moveService(from, to, focus = false) {
    if (working || from < 0 || to < 0 || from >= selections.length || to >= selections.length || from === to) return;
    const [service] = selections.splice(from, 1);
    selections.splice(to, 0, service);
    invalidate(); renderSelections();
    status.textContent = `${service.serviceId} moved to position ${to + 1}.`;
    if (focus) rows.children[to]?.querySelector('.service-drag-handle')?.focus();
  }
  function summary(data, isLoad, index) {
    const dest = destination(data.serviceId);
    const orderControls = isLoad ? null : el("div", { class: "deployment-actions" },
      el("button", { type: "button", class: "service-drag-handle", draggable: "true", title: "Drag to reorder, or use Move up/down",
        "aria-label": `Reorder ${data.serviceId}, position ${index + 1}`,
        ondragstart: event => { if (working) { event.preventDefault(); return; } draggedService = data.serviceId; event.dataTransfer.effectAllowed = "move"; event.dataTransfer.setData("text/plain", data.serviceId); },
        ondragend: () => { draggedService = null; rows.querySelectorAll('.service-drop-target').forEach(node => node.classList.remove('service-drop-target')); }
      }, "⠿"),
      el("button", { type: "button", disabled: index === 0, "aria-label": `Move ${data.serviceId} up`, onclick: () => moveService(index, index - 1, true) }, "↑"),
      el("button", { type: "button", disabled: index === selections.length - 1, "aria-label": `Move ${data.serviceId} down`, onclick: () => moveService(index, index + 1, true) }, "↓"));
    const row = el("article", { class: "run-selection" },
      el("div", {}, el("strong", {}, isLoad ? data.serviceId : `${index + 1}. ${data.serviceId}`),
        el("p", { class: "muted" }, `Image ${data.imageVersion} · Git ${data.gitReference || "prepared revision"}`),
        el("p", { class: "muted" }, `${dest?.namespace || "Unconfigured namespace"} · ${data.valuesFiles.length} values file(s)${Object.keys(data.valuesEdits || {}).length || data.overlay ? " · edited" : ""}`)),
      el("div", { class: "deployment-actions" }, orderControls, button("Edit", () => editDeployment(isLoad, index)),
        button("Remove", () => { if (isLoad) load = null; else selections.splice(index, 1); invalidate(); renderSelections(); })));
    if (!isLoad) {
      row.addEventListener('dragover', event => { if (!working && draggedService && draggedService !== data.serviceId) { event.preventDefault(); event.dataTransfer.dropEffect = 'move'; row.classList.add('service-drop-target'); } });
      row.addEventListener('dragleave', event => { if (!row.contains(event.relatedTarget)) row.classList.remove('service-drop-target'); });
      row.addEventListener('drop', event => { event.preventDefault(); row.classList.remove('service-drop-target'); if (draggedService) moveService(selections.findIndex(service => service.serviceId === draggedService), index, true); draggedService = null; });
    }
    return row;
  }
  function renderSelections() {
    rows.replaceChildren(...(selections.length ? selections.map((data, index) => summary(data, false, index))
      : [el("p", { class: "muted" }, "No service deployments added. Add the services you want to update before testing.")]));
    loadSummary.replaceChildren(load ? summary(load, true, 0) : el("p", { class: "muted" }, "Choose the load generator, image version and traffic values file."));
    chooseLoad.hidden = !!load;
  }

  function editDeployment(isLoad, index) {
    const original = isLoad ? load : selections[index];
    const used = new Set(selections.filter((_, i) => isLoad || i !== index).map(s => s.serviceId));
    if (!isLoad && load) used.add(load.serviceId);
    const available = ids.filter(id => !used.has(id));
    if (!available.length) { error.textContent = "All configured services have already been selected."; return; }
    const initial = original?.serviceId || (isLoad ? available.find(id => id.includes("load-gen")) : available.find(id => !id.includes("load-gen"))) || available[0];
    const service = select(available.map(id => [id, id]), initial);
    const revision = select([], ""), version = select([], ""), search = input("", "search", { placeholder: "Filter release tags or development hashes" });
    const filesNode = el("div", { class: "values-file-list" }), editFile = select([], "");
    const yaml = el("textarea", { rows: 13, spellcheck: "false", class: "values-editor" });
    const yamlNote = el("p", { class: "muted" });
    const legacyOverlay = el("textarea", { rows: 6, spellcheck: "false" });
    legacyOverlay.value = original?.overlay || "";
    const legacy = el("details", { hidden: !original?.overlay }, el("summary", {}, "Existing profile overlay"), labeled("Additional YAML overrides", legacyOverlay));
    const gitStatus = el("p", { role: "status", class: "muted" }), imageStatus = el("p", { role: "status", class: "muted" });
    const fileStatus = el("p", { role: "status", class: "muted" }), dialogError = el("p", { role: "alert", class: "error" });
    const commitNote = el("p", { class: "muted" });
    const save = button(original ? "Save changes" : isLoad ? "Use load generator" : "Add service", commit, true);
    const dialog = el("dialog", { class: "deployment-dialog run-service-dialog", "aria-label": isLoad ? "Configure load generator" : "Configure service" });
    activeDialog = dialog;
    let closed = false, epoch = 0, fileEpoch = 0, loadingGit = true, loadingImages = true, loadingFiles = true;
    let gitReady = false, imagesReady = false, filesReady = false, files = {}, edits = {}, selected = [], imageTags = [], pinnedCommit = "", previousRevision = "";
    let serviceBefore = service.value;
    const valid = () => !closed && !disposed;
    const updateSave = () => {
      const loading = loadingGit || loadingImages || loadingFiles;
      save.disabled = loading || !gitReady || !imagesReady || !filesReady || !version.value || !selected.length;
      retry.hidden = loading || (gitReady && imagesReady && filesReady);
    };
    function fillEditor() {
      const path = editFile.value;
      yaml.disabled = !path; yaml.value = path ? edits[path] ?? files[path] ?? "" : "";
      yamlNote.textContent = path ? "Edit this file for this run. The repository is unchanged. Later selected files override earlier ones." : "Select a values file to view and edit its YAML.";
    }
    function renderFiles() {
      const active = editFile.value;
      filesNode.replaceChildren(...Object.keys(files).sort().map(path => {
        const check = el("input", { type: "checkbox", checked: selected.includes(path) });
        check.addEventListener("change", () => {
          if (check.checked) selected.push(path); else selected = selected.filter(p => p !== path);
          renderFiles(); updateSave();
        });
        return labeled(path, check);
      }));
      editFile.replaceChildren(...selected.map((path, i) => el("option", { value: path }, `${i + 1}. ${path}${Object.hasOwn(edits, path) ? " (edited)" : ""}`)));
      editFile.value = selected.includes(active) ? active : selected[0] || ""; fillEditor();
    }
    function renderImages(preferred = version.value) {
      const visible = imageTags.filter(tag => tag.toLowerCase().includes(search.value.toLowerCase()) || tag === preferred);
      version.replaceChildren(el("option", { value: "" }, "Choose an image version"), ...visible.map(tag => el("option", { value: tag }, tag)));
      version.value = imageTags.includes(preferred) ? preferred : "";
      updateSave();
    }
    async function pages(fetchPage, cursorKey, valuesKey, first, current) {
      const items = [], seen = new Set(); let cursor = first;
      while (valid() && current()) {
        if (seen.has(String(cursor))) throw new Error("Discovery returned a repeated page. Check the connection and retry.");
        seen.add(String(cursor));
        const result = await fetchPage(cursor);
        if (!valid() || !current()) return [];
        items.push(...result[valuesKey]);
        cursor = result[cursorKey];
        if (cursor === null || cursor === undefined || cursor === "") return items;
        if (seen.size >= 200) throw new Error("More than 200 pages returned. Narrow the configured repository before retrying.");
      }
      return [];
    }
    async function loadFiles(saved, expectedEpoch = epoch) {
      const ticket = ++fileEpoch, serviceId = service.value, ref = revision.value;
      loadingFiles = true; filesReady = false; fileStatus.textContent = "Loading CKP values files…"; updateSave();
      files = {}; selected = []; edits = {}; renderFiles();
      try {
        const result = await api(`/real/services/${encodeURIComponent(serviceId)}/values`, { method: "POST", body: { revision: ref } });
        if (!valid() || ticket !== fileEpoch || expectedEpoch !== epoch) return;
        files = result.valuesFiles; pinnedCommit = result.commit;
        const paths = Object.keys(files), defaults = destination(serviceId)?.valuesFiles || [];
        const envFile = paths.find(path => path.startsWith(catalog.services[serviceId].sourceProject.chartPath + "/") && path.split("/").pop() === `values-${catalog.environment}.yaml`);
        selected = saved ? (saved.valuesFiles || []).filter(path => Object.hasOwn(files, path))
          : envFile ? [envFile] : defaults.filter(path => Object.hasOwn(files, path));
        if (!selected.length && !saved && paths.length === 1) selected = [paths[0]];
        edits = Object.fromEntries(Object.entries(saved?.valuesEdits || {}).filter(([path]) => Object.hasOwn(files, path)));
        fileStatus.textContent = paths.length ? `${paths.length} values files available. Select files in the order they should apply.` : "No values*.yaml or values*.yml files found under CKP.";
        if (saved?.valuesFiles?.some(path => !Object.hasOwn(files, path))) fileStatus.textContent += " Some saved files no longer exist; select replacements.";
        if (saved && saved.revision !== result.commit) fileStatus.textContent += " This reference now points to a different commit. Review the refreshed YAML and any retained edits before saving.";
        commitNote.textContent = `Source pinned to ${pinnedCommit.slice(0, 12)} for this run.`;
        filesReady = true; renderFiles();
      } catch (reason) { if (valid() && ticket === fileEpoch && expectedEpoch === epoch) fileStatus.textContent = reason.message; }
      finally { if (valid() && ticket === fileEpoch && expectedEpoch === epoch) { loadingFiles = false; updateSave(); } }
    }
    async function discover(saved) {
      const ticket = ++epoch, serviceId = service.value;
      fileEpoch++; gitReady = imagesReady = filesReady = false; loadingGit = loadingImages = loadingFiles = true;
      revision.replaceChildren(); version.replaceChildren(); files = {}; edits = {}; selected = []; search.value = ""; imageTags = []; renderFiles();
      gitStatus.textContent = "Loading branches and tags…"; imageStatus.textContent = "Loading image versions…"; fileStatus.textContent = "Waiting for Git reference…"; commitNote.textContent = ""; updateSave();
      const git = async () => {
        try {
          const groups = await Promise.all(["branches", "tags"].map(kind => pages(start => api(`/service-projects/${encodeURIComponent(serviceId)}/references/query`, { method: "POST", body: { kind, start, authentication: null } }), "nextStart", "values", 0, () => ticket === epoch)));
          if (!valid() || ticket !== epoch) return;
          groups.forEach((refs, i) => revision.append(el("optgroup", { label: i === 0 ? "Branches" : "Tags" }, refs.map(ref => el("option", { value: ref.id }, ref.displayName)))));
          const refs = groups.flat(), configured = catalog.services[serviceId].sourceProject.revision;
          const preferred = saved?.gitReference || saved?.revision;
          const match = refs.find(ref => ref.id === preferred || ref.displayName === preferred);
          const fallback = groups[0].find(ref => ref.displayName === "master") || refs.find(ref => ref.id === configured || ref.displayName === configured) || refs[0];
          revision.value = match?.id || fallback?.id || "";
          if (!revision.value) throw new Error("No Git branches or tags are available for this service.");
          previousRevision = revision.value; gitReady = true; gitStatus.textContent = "Branches and tags loaded. Master is selected by default when available.";
          await loadFiles(saved, ticket);
        } catch (reason) { if (valid() && ticket === epoch) { gitStatus.textContent = reason.message; loadingFiles = false; } }
        finally { if (valid() && ticket === epoch) { loadingGit = false; updateSave(); } }
      };
      const images = async () => {
        try {
          const tags = await pages(cursor => api(`/registry-sources/${encodeURIComponent("service:" + serviceId)}/services/${encodeURIComponent(serviceId)}/images/query`, { method: "POST", body: { cursor, limit: 50, authentication: null } }), "nextCursor", "versions", "", () => ticket === epoch);
          if (!valid() || ticket !== epoch) return;
          imageTags = sortImageVersions(tags); imagesReady = true; renderImages(saved?.imageVersion || "");
          imageStatus.textContent = imageTags.length ? `${imageTags.length} versions, including release tags and development hashes.` : "No image versions found.";
          if (saved?.imageVersion && !imageTags.includes(saved.imageVersion)) imageStatus.textContent += " The saved version is no longer available; choose another.";
        } catch (reason) { if (valid() && ticket === epoch) imageStatus.textContent = reason.message; }
        finally { if (valid() && ticket === epoch) { loadingImages = false; updateSave(); } }
      };
      await Promise.all([git(), images()]);
    }
    function discardChanges() { return !(Object.keys(edits).length || legacyOverlay.value.trim()) || window.confirm("Discard YAML changes before changing the service or Git reference?"); }
    service.addEventListener("change", () => { if (!discardChanges()) { service.value = serviceBefore; return; } serviceBefore = service.value; legacyOverlay.value = ""; legacy.hidden = true; discover(); });
    revision.addEventListener("change", () => { if (!discardChanges()) { revision.value = previousRevision; return; } previousRevision = revision.value; legacyOverlay.value = ""; loadFiles(); });
    search.addEventListener("input", () => renderImages()); version.addEventListener("change", updateSave);
    editFile.addEventListener("change", fillEditor);
    yaml.addEventListener("input", () => { if (editFile.value) { if (yaml.value === files[editFile.value]) delete edits[editFile.value]; else edits[editFile.value] = yaml.value; } });
    function commit() {
      if (save.disabled) return;
      const data = { serviceId: service.value, revision: pinnedCommit, gitReference: revision.value, imageVersion: version.value, valuesFiles: [...selected], overlay: legacyOverlay.value,
        valuesEdits: Object.fromEntries(selected.filter(path => Object.hasOwn(edits, path)).map(path => [path, edits[path]])) };
      if (isLoad) load = data; else if (original) selections[index] = data; else selections.push(data);
      invalidate(); renderSelections(); dialog.close();
    }
    const reset = button("Restore file", () => { delete edits[editFile.value]; fillEditor(); });
    const earlier = button("Apply earlier", () => { const i = selected.indexOf(editFile.value); if (i > 0) { [selected[i - 1], selected[i]] = [selected[i], selected[i - 1]]; renderFiles(); } });
    const retry = button("Retry discovery", () => { if (!discardChanges()) return; discover(original?.serviceId === service.value ? original : undefined); });
    dialog.append(el("div", { class: "dialog-heading" }, el("h2", {}, isLoad ? "Load generator" : original ? "Edit service" : "Add service"), button("Cancel", () => dialog.close())),
      labeled("Service", service), el("div", { class: "form-grid spacer" }, labeled("Git reference", revision), labeled("Image version", version)),
      gitStatus, labeled("Find an image version", search), imageStatus,
      el("h3", {}, "Values files"), fileStatus, filesNode,
      el("div", { class: "values-editor-heading" }, labeled("View / edit selected file (apply order)", editFile), earlier, reset),
      yamlNote, labeled("Values YAML", yaml), legacy, commitNote, dialogError,
      el("div", { class: "dialog-actions" }, retry, save));
    dialog.addEventListener("close", () => { closed = true; epoch++; fileEpoch++; dialog.remove(); if (activeDialog === dialog) activeDialog = null; });
    document.body.append(dialog); dialog.showModal(); discover(original);
  }

  const add = button("Add service", () => editDeployment(false, -1));
  const chooseLoad = button("Choose load generator", () => editDeployment(true, 0));
  const savedProfiles = await api("/real/profiles");
  const savedSelect = select([["", "New profile"], ...savedProfiles.map(p => [p.id, p.profile.name])], "");
  const readProfile = () => {
    if (!load) throw new Error("Choose a load generator before saving or preparing this run.");
    return { name: name.value, services: selections, loadGenerator: load, warmupSeconds: Number(warmup.value), measurementSeconds: Number(duration.value), maxRunDurationSeconds: Number(deadline.value),
      metrics: monitoring.read(), thresholds: monitoring.readThresholds() };
  };
  savedSelect.addEventListener("change", () => {
    const saved = savedProfiles.find(p => p.id === savedSelect.value); savedId = saved?.id || null; savedRevision = saved?.revision || null; invalidate();
    const p = saved?.profile;
    name.value = p?.name || "Performance test"; warmup.value = p?.warmupSeconds ?? 0; duration.value = p?.measurementSeconds ?? 60; deadline.value = p?.maxRunDurationSeconds ?? 900;
    selections = structuredClone(p?.services || []); load = p?.loadGenerator ? structuredClone(p.loadGenerator) : null;
    monitoring.set(p?.metrics || [], p?.thresholds || []); renderSelections();
  });
  const saveProfile = button("Save profile", async () => {
    saveProfile.disabled = true; error.textContent = ""; status.textContent="Saving run profile…";
    try {
      const result = await api("/real/profiles", { method: "POST", body: { id: savedId, revision: savedRevision, profile: readProfile() } });
      if (disposed) return;
      savedId = result.id; savedRevision = result.revision;
      const index = savedProfiles.findIndex(p => p.id === result.id); if (index < 0) savedProfiles.push(result); else savedProfiles[index] = result;
      savedSelect.replaceChildren(el("option", { value: "" }, "New profile"), ...savedProfiles.map(p => el("option", { value: p.id }, p.profile.name))); savedSelect.value = result.id;
      status.textContent = "Profile saved.";
    } catch (reason) { error.textContent = reason.message; status.textContent=""; } finally { saveProfile.disabled = false; }
  });
  const prepare = button("Review run", async () => {
    if (working) return; working = true; error.textContent = ""; invalidate();
    const controls = [...root.querySelectorAll("input,select,textarea,button:not([data-during-preparation])")]; controls.forEach(node => node.disabled = true); status.textContent = "Preparing charts and checking the cluster…";
    try {
      const profile=readProfile();const trace=await diagnostics.start();
      plan = await api("/real/plans", { method: "POST", headers:trace?{"X-Diagnostic-ID":trace}:{}, body: profile }); if (disposed) return;
      const prepared = plan, confirmed = el("input", { type: "checkbox" }), key = crypto.randomUUID();
      const run = button("Start run", async () => {
        if (plan !== prepared || !confirmed.checked) { error.textContent = "Review and confirm this plan before starting."; return; }
        run.disabled = true; error.textContent=""; status.textContent="Submitting the run…";
        try { const result = await api("/real/runs", { method: "POST", headers: { "Idempotency-Key": key }, body: { planId: prepared.id } }); if (!disposed) navigate(`#run/${result.id}`); }
        catch (reason) { error.textContent = reason.message; status.textContent=""; run.disabled = false; }
      }, true);
      preview.className = "card";
      preview.append(el("h2", {}, "Review run"), ...plan.services.map(s => el("div", { class: "run-selection" }, el("div", {}, el("strong", {}, s.serviceId), el("p", {}, `${s.namespace} / ${s.releaseName}`), el("p", { class: "muted" }, `Image ${s.image.version} · commit ${s.sourceRevision.slice(0, 12)}`),
        el("details", {}, el("summary", {}, "Prepared values"), el("pre", {}, JSON.stringify(s.effectiveValues, null, 2)))))),
        el("details", {}, el("summary", {}, "Execution details"), el("ul", {}, plan.warnings.map(text => el("li", {}, text)))),
        labeled("I reviewed the target, services and values. Start this deployment and load test.", confirmed), run);
      status.textContent = "Plan ready for review. Nothing has been deployed yet.";
    } catch (reason) { error.textContent = reason.message; status.textContent = ""; }
    finally { working = false; controls.forEach(node => node.disabled = false); renderSelections(); }
  }, true);
  root.append(notices, el("section", { class: "card" }, el("div", { class: "form-grid" }, labeled("Saved profile", savedSelect), labeled("Run name", name)),
      el("p", { class: "muted" }, `Environment: ${catalog.environment} · Context: ${settings.kubeContext}`)),
    el("section", { class: "card" }, el("div", { class: "dialog-heading" }, el("h2", {}, "Services"), add), el("p", { class: "muted" }, "Deploys from top to bottom. Drag the handle or use the arrows to reorder. The load generator starts after all services are ready."), rows),
    el("section", { class: "card" }, el("div", { class: "dialog-heading" }, el("h2", {}, "Load generator"), chooseLoad), loadSummary),
    el("section", { class: "card" }, el("h2", {}, "Test duration"), labeled("Measurement duration (seconds)", duration),
      el("p", { class: "muted" }, "How long to observe traffic after warmup. Results use this window; when it ends, the orchestrator collects metrics and stops its load generator. Traffic rate and the tool’s own duration come from its values YAML."),
      el("details", {}, el("summary", {}, "Advanced timing"), el("div", { class: "form-grid spacer" }, labeled("Warmup (seconds)", warmup), labeled("Overall timeout (seconds)", deadline)),
        el("p", { class: "muted" }, "Warmup is excluded from measurements. The overall timeout includes deployment, warmup and measurement. On timeout, cleanup starts; an in-progress Helm command and cleanup can take additional time. Allow enough time for deployment, and set the load YAML duration to cover warmup plus measurement."))),
    monitoring.node,
    el("div", {}, el("p", { class: "muted" }, "Services remain deployed. Only this run’s load-generator release is uninstalled after completion or cancellation."), el("div", { class: "card-actions" }, saveProfile, prepare, diagnostics.toggle)), preview,diagnostics.node);
  for (const field of [name, warmup, duration, deadline]) field.addEventListener("input", invalidate);
  renderSelections(); return root;
}

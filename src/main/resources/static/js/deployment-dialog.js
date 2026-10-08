import { enhanceYaml, yamlProblems } from "../vendor/yaml-editor.js";
import { sortImageVersions } from "./image-versions.js";
import { el, input, labeled, select } from "./dom.js";

export function deploymentDialog(api, catalog, { available, original, isLoad = true, onSave }) {
  const destination = (id) =>
    catalog.services[id]?.deploymentByEnvironment?.[catalog.environment] ||
    catalog.services[id]?.deploymentDefaults;
  const button = (text, action, primary = false) =>
    el("button", { type: "button", class: primary ? "primary" : "", onclick: action }, text);
  const initial =
    original?.serviceId ||
    (isLoad
      ? available.find((id) => id.includes("load-gen"))
      : available.find((id) => !id.includes("load-gen"))) ||
    available[0];
  const service = select(
    available.map((id) => [id, id]),
    initial,
  );
  const revision = select([], ""),
    version = select([], ""),
    search = input("", "search", { placeholder: "Filter release tags or development hashes" });
  const filesNode = el("div", { class: "values-file-list" }),
    editFile = select([], "");
  const yaml = el("textarea", { rows: 13, spellcheck: "false", class: "values-editor" });
  const yamlNote = el("p", { class: "muted" });
  const legacyOverlay = el("textarea", { rows: 6, spellcheck: "false" });
  legacyOverlay.value = original?.overlay || "";
  const legacy = el(
    "details",
    { hidden: !original?.overlay },
    el("summary", {}, "Existing profile overlay"),
    labeled("Additional YAML overrides", legacyOverlay),
  );
  const gitStatus = el("p", { role: "status", class: "muted" }),
    imageStatus = el("p", { role: "status", class: "muted" });
  const fileStatus = el("p", { role: "status", class: "muted" }),
    dialogError = el("p", { role: "alert", class: "error", hidden: true });
  const commitNote = el("p", { class: "muted" });
  const save = button(
    original ? "Save changes" : isLoad ? "Use load generator" : "Add service",
    commit,
    true,
  );
  const dialog = el("dialog", {
    class: "deployment-dialog run-service-dialog",
    "aria-label": isLoad ? "Configure load generator" : "Configure service",
  });

  let closed = false,
    epoch = 0,
    fileEpoch = 0,
    loadingGit = true,
    loadingImages = true,
    loadingFiles = true;
  let gitReady = false,
    imagesReady = false,
    filesReady = false,
    files = {},
    edits = {},
    selected = [],
    imageTags = [],
    pinnedCommit = "",
    previousRevision = "";
  let serviceBefore = service.value;
  const valid = () => !closed;
  const updateSave = () => {
    const loading = loadingGit || loadingImages || loadingFiles;
    save.disabled =
      loading || !gitReady || !imagesReady || !filesReady || !version.value || !selected.length;
    retry.hidden = loading || (gitReady && imagesReady && filesReady);
  };
  function fillEditor() {
    const path = editFile.value;
    yaml.disabled = !path;
    yaml.value = path ? (edits[path] ?? files[path] ?? "") : "";
    yamlNote.textContent = path
      ? "Edit this file for this run. The repository is unchanged. Later selected files override earlier ones."
      : "Select a values file to view and edit its YAML.";
  }
  function renderFiles() {
    const active = editFile.value;
    filesNode.replaceChildren(
      ...Object.keys(files)
        .sort()
        .map((path) => {
          const check = el("input", { type: "checkbox", checked: selected.includes(path) });
          check.addEventListener("change", () => {
            if (check.checked) selected.push(path);
            else selected = selected.filter((p) => p !== path);
            renderFiles();
            updateSave();
          });
          return labeled(path, check);
        }),
    );
    editFile.replaceChildren(
      ...selected.map((path, i) =>
        el(
          "option",
          { value: path },
          `${i + 1}. ${path}${Object.hasOwn(edits, path) ? " (edited)" : ""}`,
        ),
      ),
    );
    editFile.value = selected.includes(active) ? active : selected[0] || "";
    fillEditor();
  }
  function renderImages(preferred = version.value) {
    const visible = imageTags.filter(
      (tag) => tag.toLowerCase().includes(search.value.toLowerCase()) || tag === preferred,
    );
    version.replaceChildren(
      el("option", { value: "" }, "Choose an image version"),
      ...visible.map((tag) => el("option", { value: tag }, tag)),
    );
    version.value = imageTags.includes(preferred) ? preferred : "";
    updateSave();
  }
  async function pages(fetchPage, cursorKey, valuesKey, first, current) {
    const items = [],
      seen = new Set();
    let cursor = first;
    while (valid() && current()) {
      if (seen.has(String(cursor)))
        throw new Error("Discovery returned a repeated page. Check the connection and retry.");
      seen.add(String(cursor));
      const result = await fetchPage(cursor);
      if (!valid() || !current()) return [];
      items.push(...result[valuesKey]);
      cursor = result[cursorKey];
      if (cursor === null || cursor === undefined || cursor === "") return items;
      if (seen.size >= 200)
        throw new Error(
          "More than 200 pages returned. Narrow the configured repository before retrying.",
        );
    }
    return [];
  }
  async function loadFiles(saved, expectedEpoch = epoch) {
    const ticket = ++fileEpoch,
      serviceId = service.value,
      ref = revision.value;
    loadingFiles = true;
    filesReady = false;
    fileStatus.textContent = "Loading CKP values files…";
    updateSave();
    files = {};
    selected = [];
    edits = {};
    renderFiles();
    try {
      const result = await api(`/real/services/${encodeURIComponent(serviceId)}/values`, {
        method: "POST",
        body: { revision: ref },
      });
      if (!valid() || ticket !== fileEpoch || expectedEpoch !== epoch) return;
      files = result.valuesFiles;
      pinnedCommit = result.commit;
      const paths = Object.keys(files),
        defaults = destination(serviceId)?.valuesFiles || [];
      const envFile = paths.find(
        (path) =>
          path.startsWith(catalog.services[serviceId].sourceProject.chartPath + "/") &&
          path.split("/").pop() === `values-${catalog.environment}.yaml`,
      );
      selected = saved
        ? (saved.valuesFiles || []).filter((path) => Object.hasOwn(files, path))
        : envFile
          ? [envFile]
          : defaults.filter((path) => Object.hasOwn(files, path));
      if (!selected.length && !saved && paths.length === 1) selected = [paths[0]];
      edits = Object.fromEntries(
        Object.entries(saved?.valuesEdits || {}).filter(([path]) => Object.hasOwn(files, path)),
      );
      fileStatus.textContent = paths.length
        ? `${paths.length} values files available. Select files in the order they should apply.`
        : "No values*.yaml or values*.yml files found under CKP.";
      if (saved?.valuesFiles?.some((path) => !Object.hasOwn(files, path)))
        fileStatus.textContent += " Some saved files no longer exist; select replacements.";
      if (saved && saved.revision !== result.commit)
        fileStatus.textContent +=
          " This reference now points to a different commit. Review the refreshed YAML and any retained edits before saving.";
      commitNote.textContent = `Source pinned to ${pinnedCommit.slice(0, 12)} for this run.`;
      filesReady = true;
      renderFiles();
    } catch (reason) {
      if (valid() && ticket === fileEpoch && expectedEpoch === epoch)
        fileStatus.textContent = reason.message;
    } finally {
      if (valid() && ticket === fileEpoch && expectedEpoch === epoch) {
        loadingFiles = false;
        updateSave();
      }
    }
  }
  async function discover(saved) {
    const ticket = ++epoch,
      serviceId = service.value;
    fileEpoch++;
    gitReady = imagesReady = filesReady = false;
    loadingGit = loadingImages = loadingFiles = true;
    revision.replaceChildren();
    version.replaceChildren();
    files = {};
    edits = {};
    selected = [];
    search.value = "";
    imageTags = [];
    renderFiles();
    gitStatus.textContent = "Loading branches and tags…";
    imageStatus.textContent = "Loading image versions…";
    fileStatus.textContent = "Waiting for Git reference…";
    commitNote.textContent = "";
    updateSave();
    const git = async () => {
      try {
        const groups = await Promise.all(
          ["branches", "tags"].map((kind) =>
            pages(
              (start) =>
                api(`/service-projects/${encodeURIComponent(serviceId)}/references/query`, {
                  method: "POST",
                  body: { kind, start, authentication: null },
                }),
              "nextStart",
              "values",
              0,
              () => ticket === epoch,
            ),
          ),
        );
        if (!valid() || ticket !== epoch) return;
        groups.forEach((refs, i) =>
          revision.append(
            el(
              "optgroup",
              { label: i === 0 ? "Branches" : "Tags" },
              refs.map((ref) => el("option", { value: ref.id }, ref.displayName)),
            ),
          ),
        );
        const refs = groups.flat(),
          configured = catalog.services[serviceId].sourceProject.revision;
        const preferred = saved?.gitReference || saved?.revision;
        const match = refs.find((ref) => ref.id === preferred || ref.displayName === preferred);
        const fallback =
          groups[0].find((ref) => ref.displayName === "master") ||
          refs.find((ref) => ref.id === configured || ref.displayName === configured) ||
          refs[0];
        revision.value = match?.id || fallback?.id || "";
        if (!revision.value)
          throw new Error("No Git branches or tags are available for this service.");
        previousRevision = revision.value;
        gitReady = true;
        gitStatus.textContent =
          "Branches and tags loaded. Master is selected by default when available.";
        await loadFiles(saved, ticket);
      } catch (reason) {
        if (valid() && ticket === epoch) {
          gitStatus.textContent = reason.message;
          loadingFiles = false;
        }
      } finally {
        if (valid() && ticket === epoch) {
          loadingGit = false;
          updateSave();
        }
      }
    };
    const images = async () => {
      try {
        const tags = await pages(
          (cursor) =>
            api(
              `/registry-sources/${encodeURIComponent("service:" + serviceId)}/services/${encodeURIComponent(serviceId)}/images/query`,
              { method: "POST", body: { cursor, limit: 50, authentication: null } },
            ),
          "nextCursor",
          "versions",
          "",
          () => ticket === epoch,
        );
        if (!valid() || ticket !== epoch) return;
        imageTags = sortImageVersions(tags);
        imagesReady = true;
        renderImages(saved?.imageVersion || "");
        imageStatus.textContent = imageTags.length
          ? `${imageTags.length} versions, including release tags and development hashes.`
          : "No image versions found.";
        if (saved?.imageVersion && !imageTags.includes(saved.imageVersion))
          imageStatus.textContent += " The saved version is no longer available; choose another.";
      } catch (reason) {
        if (valid() && ticket === epoch) imageStatus.textContent = reason.message;
      } finally {
        if (valid() && ticket === epoch) {
          loadingImages = false;
          updateSave();
        }
      }
    };
    await Promise.all([git(), images()]);
  }
  function discardChanges() {
    return (
      !(Object.keys(edits).length || legacyOverlay.value.trim()) ||
      window.confirm("Discard YAML changes before changing the service or Git reference?")
    );
  }
  service.addEventListener("change", () => {
    if (!discardChanges()) {
      service.value = serviceBefore;
      return;
    }
    serviceBefore = service.value;
    legacyOverlay.value = "";
    legacy.hidden = true;
    discover();
  });
  revision.addEventListener("change", () => {
    if (!discardChanges()) {
      revision.value = previousRevision;
      return;
    }
    previousRevision = revision.value;
    legacyOverlay.value = "";
    loadFiles();
  });
  search.addEventListener("input", () => renderImages());
  version.addEventListener("change", updateSave);
  editFile.addEventListener("change", fillEditor);
  yaml.addEventListener("input", () => {
    if (editFile.value) {
      if (yaml.value === files[editFile.value]) delete edits[editFile.value];
      else edits[editFile.value] = yaml.value;
    }
  });
  function commit() {
    if (save.disabled) return;
    const invalid = selected.find((path) => yamlProblems(edits[path] ?? files[path]).length);
    if (invalid) {
      dialogError.hidden = false;
      dialogError.textContent = `Fix YAML errors in ${invalid} before saving.`;
      dialogError.scrollIntoView({ block: "nearest" });
      return;
    }
    dialogError.textContent = "";
    dialogError.hidden = true;
    const data = {
      serviceId: service.value,
      revision: pinnedCommit,
      gitReference: revision.value,
      imageVersion: version.value,
      valuesFiles: [...selected],
      overlay: legacyOverlay.value,
      valuesEdits: Object.fromEntries(
        selected.filter((path) => Object.hasOwn(edits, path)).map((path) => [path, edits[path]]),
      ),
    };
    onSave(data);
    dialog.close();
  }
  const reset = button("Restore file", () => {
    delete edits[editFile.value];
    fillEditor();
  });
  const earlier = button("Apply earlier", () => {
    const i = selected.indexOf(editFile.value);
    if (i > 0) {
      [selected[i - 1], selected[i]] = [selected[i], selected[i - 1]];
      renderFiles();
    }
  });
  const retry = button("Retry discovery", () => {
    if (!discardChanges()) return;
    discover(original?.serviceId === service.value ? original : undefined);
  });
  dialog.append(
    el(
      "div",
      { class: "dialog-heading" },
      el("h2", {}, isLoad ? "Load generator" : original ? "Edit service" : "Add service"),
      button("Cancel", () => dialog.close()),
    ),
    dialogError,
    labeled("Service", service),
    el(
      "div",
      { class: "form-grid spacer" },
      labeled("Git reference", revision),
      labeled("Image version", version),
    ),
    gitStatus,
    labeled("Find an image version", search),
    imageStatus,
    el("h3", {}, "Values files"),
    fileStatus,
    filesNode,
    el(
      "div",
      { class: "values-editor-heading" },
      labeled("View / edit selected file (apply order)", editFile),
      earlier,
      reset,
    ),
    yamlNote,
    labeled("Values YAML", yaml),
    legacy,
    commitNote,
    el("div", { class: "dialog-actions" }, retry, save),
  );
  const yamlEditor = enhanceYaml(yaml);
  dialog.addEventListener("close", () => {
    yamlEditor.dispose();
    closed = true;
    epoch++;
    fileEpoch++;
    dialog.remove();
  });
  document.body.append(dialog);
  dialog.showModal();
  discover(original);
  return dialog;
}

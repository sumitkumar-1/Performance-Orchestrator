import { el } from "./dom.js";

export function confirmEnvironmentCleanup(preview, onDialog) {
  return new Promise((resolve) => {
    let confirmed = false;
    const existing = preview.releases.filter((release) => release.baseline !== "ABSENT");
    const acknowledgement = el("input", { type: "checkbox" });
    const proceed = el(
      "button",
      { type: "button", class: existing.length ? "danger" : "primary" },
      existing.length
        ? `Uninstall ${existing.length} release${existing.length === 1 ? "" : "s"} & review`
        : "Continue to review",
    );
    proceed.disabled = existing.length > 0;
    acknowledgement.addEventListener("change", () => {
      proceed.disabled = !acknowledgement.checked;
    });
    const dialog = el(
      "dialog",
      {
        class: "deployment-dialog environment-cleanup-dialog",
        "aria-labelledby": "environment-cleanup-title",
      },
      el("h2", { id: "environment-cleanup-title" }, "Clean up before review"),
      el("p", {}, `Environment: ${preview.environment} · Context: ${preview.target.context}`),
      el(
        "p",
        { class: "muted" },
        "Only the releases listed below are in scope. Pre-existing releases are included. Helm uninstall can remove workloads and release-managed data. This cannot be undone automatically.",
      ),
      el(
        "div",
        { class: "run-selections" },
        preview.releases.map((release) =>
          el(
            "article",
            { class: "run-selection" },
            el(
              "div",
              {},
              el("strong", {}, release.service),
              el("p", {}, `${release.namespace} / ${release.release}`),
            ),
            el(
              "span",
              { class: "pill" },
              release.baseline === "ABSENT" ? "Already absent" : "Will uninstall",
            ),
          ),
        ),
      ),
      existing.length
        ? el(
            "label",
            { class: "cleanup-confirmation" },
            acknowledgement,
            "I confirm uninstalling these releases before preparing the run.",
          )
        : el("p", {}, "No selected releases are installed. Nothing will be removed."),
    );
    const cancel = el("button", { type: "button", onclick: () => dialog.close() }, "Cancel");
    proceed.addEventListener("click", () => {
      confirmed = true;
      dialog.close();
    });
    dialog.append(el("div", { class: "card-actions spacer" }, cancel, proceed));
    dialog.addEventListener(
      "close",
      () => {
        dialog.remove();
        resolve(confirmed);
      },
      { once: true },
    );
    document.body.append(dialog);
    onDialog(dialog);
    dialog.showModal();
    cancel.focus();
  });
}

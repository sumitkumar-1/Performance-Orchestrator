import { EditorView, basicSetup } from "codemirror";
import { EditorState, Compartment } from "@codemirror/state";
import { yaml } from "@codemirror/lang-yaml";
import { linter, lintGutter } from "@codemirror/lint";
import { parseDocument } from "yaml";

export function yamlProblems(text) {
  const document = parseDocument(text, { uniqueKeys: true });
  return document.errors.map((error) => ({
    from: Math.min(error.pos?.[0] || 0, text.length),
    to: Math.min(error.pos?.[1] || text.length, text.length),
    severity: "error",
    message: error.message,
  }));
}

/** A locally bundled editor that preserves the textarea's value/input contract. */
export function enhanceYaml(textarea) {
  const host = document.createElement("div");
  host.className = "yaml-code-editor";
  textarea.after(host);
  textarea.hidden = true;
  const editable = new Compartment();
  let syncing = false;
  const view = new EditorView({
    parent: host,
    doc: textarea.value,
    extensions: [
      EditorView.cspNonce.of(document.documentElement.dataset.styleNonce || ""),
      basicSetup,
      yaml(),
      lintGutter(),
      linter((editor) => yamlProblems(editor.state.doc.toString())),
      editable.of(EditorState.readOnly.of(textarea.disabled)),
      EditorState.tabSize.of(2),
      EditorView.contentAttributes.of({ "aria-label": "Values YAML editor", spellcheck: "false" }),
      EditorView.updateListener.of((update) => {
        if (update.docChanged && !syncing) {
          nativeValue.set.call(textarea, update.state.doc.toString());
          textarea.dispatchEvent(new Event("input", { bubbles: true }));
        }
      }),
      EditorView.theme({
        "&": { border: "1px solid #cbd8d1", borderRadius: "8px", fontSize: "13px" },
        ".cm-scroller": {
          overflow: "auto",
          maxHeight: "420px",
          minHeight: "230px",
          fontFamily: "ui-monospace, monospace",
        },
        ".cm-content": { padding: "10px 0" },
        ".cm-gutters": { backgroundColor: "#f4f7f5" },
      }),
    ],
  });
  const nativeValue = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value");
  Object.defineProperty(textarea, "value", {
    configurable: true,
    get() {
      return nativeValue.get.call(this);
    },
    set(value) {
      nativeValue.set.call(this, value);
      syncing = true;
      try {
        view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: value || "" } });
      } finally {
        syncing = false;
      }
    },
  });
  const observer = new MutationObserver(() =>
    view.dispatch({ effects: editable.reconfigure(EditorState.readOnly.of(textarea.disabled)) }),
  );
  observer.observe(textarea, { attributes: true, attributeFilter: ["disabled"] });
  return {
    valid: () => yamlProblems(textarea.value).length === 0,
    focus: () => view.focus(),
    dispose() {
      observer.disconnect();
      view.destroy();
      host.remove();
      delete textarea.value;
      textarea.hidden = false;
    },
  };
}

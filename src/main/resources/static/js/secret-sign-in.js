import { el, labeled, input, select } from "./dom.js";

// This prompts for vault access; it is not shared-user portal authorization.
export function secretSignInPrompt(api, onSignedIn) {
  let timer, dialog, checking = false;
  const dismissed = new Set();
  async function refresh() {
    if (checking) return;
    checking = true;
    try {
      const states = await api("/secret-auth");
      clearTimeout(timer);
      let nextExpiry = Infinity;
      for (const [id, state] of Object.entries(states)) {
        if (state.state === "AUTHENTICATED") {
          dismissed.delete(id);
          nextExpiry = Math.min(nextExpiry, Date.parse(state.expiresAt));
        }
      }
      if (Number.isFinite(nextExpiry))
        timer = setTimeout(() => refresh().catch(() => {}), Math.min(2147483647, Math.max(1000, nextExpiry - Date.now() + 1000)));
      const pending = Object.entries(states).filter(([id, state]) =>
        state.mode === "portal" && state.state !== "AUTHENTICATED" && !dismissed.has(id));
      if (!pending.length || dialog?.open) return;
      const connection = select(pending.map(([id]) => [id, id]), pending[0][0]);
      const username = input("", "text", { autocomplete: "username", required: true, maxlength: 512 });
      const password = input("", "password", { autocomplete: "current-password", required: true, maxlength: 4096 });
      const status = el("p", { role: "status", class: "muted" });
      const submit = el("button", { type: "submit", class: "primary" }, "Sign in");
      const close = () => { pending.forEach(([id]) => dismissed.add(id)); activeDialog.close(); };
      const form = el("form", {},
        el("h2", { id: "vault-signin-title" }, "Sign in to Secret Server"),
        el("p", { class: "muted" }, "Use your AD credentials to retrieve configured service secrets. The token stays on the server until it expires. Configure your organization's vault URL before signing in."),
        labeled("Secret Server connection", connection), labeled("AD username", username),
        labeled("AD password", password), status,
        el("div", { class: "card-actions" }, submit,
          el("button", { type: "button", onclick: () => { close(); location.hash = "#settings"; } }, "Configure connections"),
          el("button", { type: "button", onclick: close }, "Not now")));
      dialog = el("dialog", { class: "deployment-dialog", "aria-labelledby": "vault-signin-title" }, form);
      const activeDialog = dialog;
      dialog.addEventListener("cancel", event => { event.preventDefault(); close(); });
      dialog.addEventListener("close", () => { password.value = ""; activeDialog.remove(); });
      connection.addEventListener("change", () => { password.value = ""; status.textContent = ""; });
      form.addEventListener("submit", async event => {
        event.preventDefault();
        if (submit.disabled) return;
        submit.disabled = true;
        const id = connection.value;
        const credentials = { username: username.value, password: password.value };
        password.value = "";
        try {
          const authenticated = await api(`/secret-auth/${encodeURIComponent(id)}`, { method: "POST", body: credentials });
          activeDialog.close();
          await onSignedIn(authenticated);
          await refresh();
        } catch (error) { status.textContent = error.message; }
        finally { credentials.password = ""; submit.disabled = false; }
      });
      document.body.append(dialog);
      dialog.showModal();
      username.focus();
    } finally { checking = false; }
  }
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden) refresh().catch(() => {});
  });
  return { refresh };
}

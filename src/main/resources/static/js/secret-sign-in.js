import { el, labeled, input, select } from "./dom.js";

// This prompts for vault access; it is not shared-user portal authorization.
export function secretSignInPrompt(api, onSignedIn, automatic = true) {
  let timer, dialog, checking = false;
  const dismissed = new Set();
  async function refresh(preferred) {
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
      const connection = select(pending.map(([id]) => [id, id]), pending.some(([id]) => id === preferred) ? preferred : pending[0][0]);
      const username = input("", "text", { autocomplete: "username", required: true, maxlength: 512 });
      const password = input("", "password", { autocomplete: "current-password", required: true, maxlength: 4096 });
      const status = el("p", { role: "status", class: "muted" });
      const submit = el("button", { type: "submit", class: "primary" }, "Sign in");
      const close = () => { pending.forEach(([id]) => dismissed.add(id)); activeDialog.close(); };
      const form = el("form", {},
        el("h2", { id: "vault-signin-title" }, "Sign in to Secret Server"),
        el("p", { class: "muted" }, "Use your AD account to access service secrets. Your session token is held on the server until expiry."),
        pending.length > 1 ? labeled("Secret Server", connection) : el("p", { class: "vault-connection" }, `Connection: ${pending[0][0]}`), labeled("AD username", username),
        labeled("AD password", password), status,
        el("div", { class: "dialog-actions" },
          el("button", { type: "button", onclick: close }, "Not now"), submit),
        el("a", { href: "#settings", onclick: close, class: "vault-settings-link" }, "Manage vault connection settings"));
      dialog = el("dialog", { class: "deployment-dialog auth-dialog", "aria-labelledby": "vault-signin-title" }, form);
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
    if (automatic && !document.hidden) refresh().catch(() => {});
  });
  return { refresh, open: async id => { if (id) dismissed.delete(id); else dismissed.clear(); await refresh(id); } };
}

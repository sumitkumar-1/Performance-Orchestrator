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
        if (["AUTHENTICATED", "TOKEN_PROVIDED"].includes(state.state)) {
          dismissed.delete(id);
          nextExpiry = Math.min(nextExpiry, Date.parse(state.expiresAt));
        }
      }
      if (Number.isFinite(nextExpiry))
        timer = setTimeout(() => refresh().catch(() => {}), Math.min(2147483647, Math.max(1000, nextExpiry - Date.now() + 1000)));
      const pending = Object.entries(states).filter(([id, state]) =>
        state.mode === "token" && !["AUTHENTICATED", "TOKEN_PROVIDED"].includes(state.state) && !dismissed.has(id));
      if (!pending.length || dialog?.open) return;
      const connection = select(pending.map(([id]) => [id, id]), pending.some(([id]) => id === preferred) ? preferred : pending[0][0]);
      const token = input("", "password", { autocomplete: "off", maxlength: 65536 });
      const lifetime = input("900", "number", { min: 1, max: 28800 });
      const tokenField = labeled("Bearer token", token), lifetimeField = labeled("Remaining token lifetime (seconds, max 8 hours)", lifetime);
      const description = el("p", { class: "muted" });
      const status = el("p", { role: "status", class: "muted" });
      const submit = el("button", { type: "submit", class: "primary" }, "Sign in");
      const close = () => { pending.forEach(([id]) => dismissed.add(id)); activeDialog.close(); };
      const form = el("form", {},
        el("h2", { id: "vault-signin-title" }, "Sign in to Secret Server"),
        description,
        pending.length > 1 ? labeled("Secret Server", connection) : el("p", { class: "vault-connection" }, `Connection: ${pending[0][0]}`), tokenField, lifetimeField, status,
        el("div", { class: "dialog-actions" },
          el("button", { type: "button", onclick: close }, "Not now"), submit),
        el("a", { href: "#settings", onclick: close, class: "vault-settings-link" }, "Manage vault connection settings"));
      dialog = el("dialog", { class: "deployment-dialog auth-dialog", "aria-labelledby": "vault-signin-title" }, form);
      const activeDialog = dialog;
      dialog.addEventListener("cancel", event => { event.preventDefault(); close(); });
      const clear = () => { token.value = ""; };
      dialog.addEventListener("close", () => { clear(); activeDialog.remove(); });
      const toggle = () => {
        clear(); status.textContent = "";
        token.required = lifetime.required = true;
        submit.textContent = "Use token";
        description.textContent = "Paste a REST API Bearer access token. It stays in your server-side session only. Enter no more than its remaining lifetime; validity is checked when accessing a secret.";
      };
      connection.addEventListener("change", toggle); toggle();
      form.addEventListener("submit", async event => {
        event.preventDefault();
        if (submit.disabled) return;
        submit.disabled = true;
        const id = connection.value;
        const credentials = { token: token.value, expiresInSeconds: Number(lifetime.value) };
        clear();
        try {
          const authenticated = await api(`/secret-auth/${encodeURIComponent(id)}/token`, { method: "POST", body: credentials });
          activeDialog.close();
          await onSignedIn(authenticated);
          await refresh();
        } catch (error) { status.textContent = error.message; }
        finally { Object.keys(credentials).forEach(key => { credentials[key] = ""; }); submit.disabled = false; }
      });
      document.body.append(dialog);
      dialog.showModal();
      token.focus();
    } finally { checking = false; }
  }
  document.addEventListener("visibilitychange", () => {
    if (automatic && !document.hidden) refresh().catch(() => {});
  });
  return { refresh, open: async id => { if (id) dismissed.delete(id); else dismissed.clear(); await refresh(id); } };
}

import { el, input, labeled, select } from "./dom.js";

// Only the opaque session cookie stays in the browser; credentials are never put in browser storage.
export function operationAuthentication(connection, { api, kind, id }) {
  const mode = connection.authMode || "secret-server";
  const path = `/connection-auth/${encodeURIComponent(kind)}/${encodeURIComponent(id)}`;
  const token = input("", "password", { autocomplete: "off", maxlength: 65536 });
  const lifetime = select([["1800", "30 minutes"], ["3600", "1 hour"], ["28800", "8 hours"]], "1800");
  const status = el("p", { class: "muted", role: "status" });
  const error = el("p", { class: "error", role: "alert", hidden: true });
  let expiryTimer, disposed = false;
  const clear = () => { clearTimeout(expiryTimer); token.value = ""; };
  const fields = el("div", { class: "config-form", hidden: true },
    mode === "token" ? labeled("Bearer token", token) : null,
    labeled("Session duration (no longer than token validity)", lifetime));
  const login = el("button", { type: "button", hidden: true }, "Start connection session");
  const logout = el("button", { type: "button", hidden: true }, "Sign out of connection");
  const node = el("div", { class: "config-form" }, status, fields, el("div", { class: "card-actions" }, login, logout), error);
  let available = false;
  const render = state => {
    if (disposed) return;
    clearTimeout(expiryTimer);
    available = state.state === "CREDENTIALS_AVAILABLE";
    if (available) expiryTimer = setTimeout(() => refresh().catch(() => {}), Math.max(1000, Date.parse(state.expiresAt) - Date.now() + 50));
    fields.hidden = login.hidden = available || mode === "secret-server";
    logout.hidden = !available;
    status.textContent = mode === "secret-server" ? `Credentials are resolved through ${connection.credentialRef}.`
      : available ? `Session available until ${new Date(state.expiresAt).toLocaleString()}. Shared by services using this connection; server permissions are checked when used.`
      : "Sign in once for services using this connection. Tokens stay encrypted in server memory until expiry, sign-out or server restart.";
  };
  const refresh = async () => {
    if (mode === "secret-server") { render({ state: "REFERENCE" }); return; }
    render(await api(path));
  };
  const remember = async () => {
    const authentication = { token: token.value };
    clear();
    try { render(await api(path, { method: "POST", body: { authentication, lifetimeSeconds: Number(lifetime.value) } })); }
    finally { Object.keys(authentication).forEach(key => { authentication[key] = ""; }); }
  };
  const withError = async (control, operation) => {
    control.disabled = true; error.hidden = true;
    try { await operation(); } catch (reason) { error.hidden = false; error.textContent = reason.message; }
    finally { control.disabled = false; }
  };
  login.addEventListener("click", () => withError(login, remember));
  logout.addEventListener("click", () => withError(logout, async () => { await api(path, { method: "DELETE" }); clear(); await refresh(); }));
  refresh().catch(reason => { error.hidden = false; error.textContent = reason.message; });
  return { node, clear, refresh, dispose() { disposed = true; clear(); }, async run(operation) {
    await refresh();
    if (mode !== "secret-server" && !available) await remember();
    try { return await operation(null); }
    catch (reason) { await refresh().catch(() => {}); throw reason; }
  } };
}

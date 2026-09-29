import { el, input, labeled } from "./dom.js";

// Sensitive input is consumed once, cleared immediately, and never added to config/session/storage.
export function operationAuthentication(connection) {
  const mode = connection.authMode || "secret-server";
  const username = input("", "text", { autocomplete: "off", maxlength: 512 });
  const password = input("", "password", { autocomplete: "off", maxlength: 4096 });
  const token = input("", "password", { autocomplete: "off", maxlength: 65536 });
  const node = el("div", { class: "config-form" },
    el("p", { class: "muted" }, mode === "secret-server"
      ? `Credentials are resolved through ${connection.credentialRef}. Sign in to the configured vault first when required.`
      : "Credentials are used once for the next request, then cleared. Re-enter them for another page or request."),
    mode === "ad" ? labeled("AD username", username) : null,
    mode === "ad" ? labeled("AD password", password) : null,
    mode === "token" ? labeled("Bearer token", token) : null);
  const clear = () => { username.value = ""; password.value = ""; token.value = ""; };
  return { node, clear, async run(operation) {
    const authentication = mode === "secret-server" ? null : mode === "ad"
      ? { username: username.value, password: password.value } : { token: token.value };
    clear();
    try { return await operation(authentication); }
    finally { if (authentication) Object.keys(authentication).forEach(key => { authentication[key] = ""; }); }
  } };
}

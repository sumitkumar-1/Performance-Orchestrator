# Read-only Artifactory and Delinea connections

## Administrator-owned configuration

Packaged defaults and the runtime configuration editor define destinations, source/service/repository mappings and credential references. Runtime edits are restricted to the existing same-origin, CSRF-protected local workspace. Discovery and sign-in requests select configured identifiers; only the configuration endpoint accepts destination changes. Configuration updates require a matching revision, persist atomically, and invalidate existing portal tokens when connections change. Multiple Artifactory instances can each reference different Delinea secrets or environment secrets. The example file shows dev, release and custom-user sources; add QA/stable or additional sources as entries.

The YAML must contain `artifactory`, `secretServers`, `credentials`, and `imageSources` maps. Unknown properties are rejected. Every connection requires HTTPS without URL credentials, queries or fragments. TLS uses the JVM trust store; private organization CAs must be provisioned through approved trust-store configuration. Certificate verification cannot be disabled in the app. Redirects are rejected and never forward credentials. No repository-wide catalog scans occur.

## Delinea

A credential with `provider: delinea` has a `secretServerRef`, numeric `secretId`, `usernameFieldSlug`, and `passwordFieldSlug`. The selected server defines an API base, including the version, and exactly one authentication mode:

- `authMode: portal`: configure an independent HTTPS `tokenUrl`. Users sign in under Connections & catalog. The backend sends form-encoded `grant_type=password`, `username`, and `password` based on the supplied reference client. It requires a Bearer `access_token` and positive `expires_in`. Tokens remain in the server-side browser session, never the API response or browser storage. Passwords are used for the exchange only; their whitespace is preserved. Expiry or a 401 from secret retrieval requires signing in again. Clearing the session forgets its token locally; it does not revoke it at Delinea.
- `authMode: environment`: configure `bearerTokenEnvironmentVariable`. This remains the default for existing configurations that omit `authMode`. Restart after rotating a process environment variable.
- `authMode: file`: configure an absolute `bearerTokenFile` path to a plain bearer-token file, for example on a volume populated by an init container. The backend rereads at each secret retrieval, allowing replacement by an external rotation mechanism. Files are bounded to 64 KiB; surrounding whitespace is removed. The app does not provision or refresh this file. An init container alone does not renew tokens after startup.

Modes do not fall back to each other. Global modes are administrator-provided credentials, not tenant authorization. Portal tokens cannot be used by background workers without a request session; real run execution and delegated credentials remain future work. Sessions are in memory and do not survive application restarts.

Secret IDs can be configured for each destination, including namespace credentials such as `logs-service-a`. The example shows these as reusable named credential references. Values are fetched on demand and not cached. Namespace-to-monitoring-source selection remains part of the planned telemetry integration.

The backend performs `GET {apiBaseUrl}/secrets/{secretId}` with `Authorization: Bearer …`, then reads configured slugs from the response's `items[]`, using `itemValue`. The token is never sent to Artifactory; only the resolved username/password is sent as HTTP Basic credentials to that credential's configured Artifactory destination. An Artifactory access token can be the password value if your deployment supports that authentication mode.

This response contract must be checked against the deployed Secret Server version/template. The portal flow follows the supplied Secret Server client; other OAuth grants, automatic refresh, integrated Windows auth, SDK registration, approval workflows and checked-out secret lifecycles are not implemented. An empty token URL is invalid in portal mode; there is no implicit Basic-auth fallback to the vault. Backend errors disclose only fixed diagnostic text, never secret bodies or HTTP authorization headers.

References: [Delinea version-specific REST APIs](https://docs.delinea.com/online-help/secret-server-11-6-x/api-scripting/rest-api-reference-download/index.htm), [Delinea Platform access to Secret Server APIs](https://docs.delinea.com/online-help/delinea-platform/api/ss-apis.htm).

## Artifactory

`apiBaseUrl` ends at the approved Artifactory Docker API prefix, commonly `/artifactory/api/docker`. Each source supplies the exact repository key and a service-to-image path map. Username sources may use `{username}` in registered image paths. Artifact-owner usernames are validated as 1–40 letters, digits, hyphens/underscores and are never treated as authentication credentials.

Requests use the documented Docker V2 routes:

- `GET {apiBaseUrl}/{repositoryKey}/v2/{imagePath}/tags/list?n={limit}&last={cursor}`.
- `GET {apiBaseUrl}/{repositoryKey}/v2/{imagePath}/manifests/{tag}`.

Tag pages are limited to 100 entries. A full page returns its final tag as the next cursor; a final empty page can therefore be needed. No server-provided pagination URL is followed. Ordering is the registry's lexical ordering, not publication recency. No timestamps are invented. Selection does not automatically choose or deploy the newest version. Manifest bodies are hashed and must match the returned SHA-256 digest; image-index digests remain index identities and are not incorrectly compared with platform manifests.

Reference: [JFrog List Docker Tags](https://docs.jfrog.com/artifactory/reference/listdockertags), [JFrog Docker repositories](https://docs.jfrog.com/artifactory/docs/docker-repositories).

Connections are bounded by a 5-second connect timeout, 15-second total response deadline and 2 MiB response limit. Retries, automatic bearer challenge negotiation, registry promotion, AQL, private-result caches and write APIs are not implemented. Since there is no cache, private results cannot cross cache authorization scopes. Local mode has one developer identity; team authorization is a prerequisite for exposing these read-only credentials through a shared deployment.

## Execution boundary

The real read-only registry browser is available in Settings and via the REST API. The simulation editor retains fixture image sources. Real registry results do not implicitly authorize deployment and are not attached to simulated plans as real deployed evidence. Linking real selected images to project-owned charts requires confirmed source-selector/Helm/digest bindings and actual rendering checks.

## Secret Server session API

- `GET /api/v1/secret-auth`: authentication mode and session status for each registered server, without credentials or tokens. Global-provider status is not a live connectivity check.
- `POST /api/v1/secret-auth/{connection}`: JSON username/password for a registered portal connection. Returns status/expiry only and rotates the browser session ID. The existing same-origin and CSRF checks apply.
- `DELETE /api/v1/secret-auth/{connection}`: clear this browser's token for that connection. CSRF is required.

Sign-in requests cannot supply an outbound token URL; configure it through runtime settings or startup defaults. Error bodies from the token/secret servers are not returned or logged. No live authentication has been validated yet.

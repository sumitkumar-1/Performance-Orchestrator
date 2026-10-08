# Artifactory, Bitbucket and Delinea connections

## Administrator-owned configuration

Packaged defaults and the runtime configuration editor define destinations, source/service/repository mappings and credential references. Runtime edits are restricted to the existing same-origin, CSRF-protected local workspace. Discovery and sign-in requests select configured identifiers; only the configuration endpoint accepts destination changes. Configuration updates require a matching revision, persist atomically, and invalidate existing portal tokens when connections change. Multiple Artifactory instances can each reference different Delinea secrets or environment secrets. The example file shows dev, release and custom-user sources; add QA/stable or additional sources as entries.

The YAML contains `artifactory`, `bitbucket`, `secretServers`, `credentials`, and legacy `imageSources` maps. Older imports without `bitbucket` remain readable. New Docker image and source repository mappings belong to the service catalog. Unknown properties are rejected. Every connection requires HTTPS without URL credentials, queries or fragments. TLS uses the JVM trust store; private organization CAs must be provisioned through approved trust-store configuration. Certificate verification cannot be disabled in the app. Redirects are rejected and never forward credentials. No repository-wide catalog scans occur.

## Delinea

A credential with `provider: delinea` has a `secretServerRef`, numeric `secretId`, and either `tokenFieldSlug` or namespace username/password field slugs. The selected server defines an API base, including the version, and exactly one authentication mode:

- `authMode: token`: users enter a Bearer token and its remaining lifetime in the sign-in dialog. It stays only in server session memory, capped at 8 hours. The status is `TOKEN_PROVIDED`, not a claim of successful upstream authentication; actual validity is checked when retrieving a secret.
- `authMode: secret-server`: configure a token credential reference from another vault or environment provider. Password exchange is not supported; vault dependency cycles are rejected.
- `authMode: environment`: configure `bearerTokenEnvironmentVariable`. This remains the default for existing configurations that omit `authMode`. Restart after rotating a process environment variable.
- `authMode: file`: configure an absolute `bearerTokenFile` path to a plain bearer-token file, for example on a volume populated by an init container. The backend rereads at each secret retrieval, allowing replacement by an external rotation mechanism. Files are bounded to 64 KiB; surrounding whitespace is removed. The app does not provision or refresh this file. An init container alone does not renew tokens after startup.

Modes do not fall back to each other. Global modes are administrator-provided credentials, not tenant authorization. Portal tokens cannot be used by background workers without a request session; real run execution and delegated credentials remain future work. Sessions are in memory and do not survive application restarts.

Secret IDs can be configured for each destination, including namespace credentials such as `logs-service-a`. The example shows these as reusable named credential references. Values are fetched on demand and not cached. Namespace-to-monitoring-source selection remains part of the planned telemetry integration.

The backend performs `GET {apiBaseUrl}/secrets/{secretId}` with `Authorization: Bearer …`, then reads configured slugs from the response's `items[]`, using `itemValue`. The vault session token is never sent to Artifactory or Bitbucket. Artifactory/Bitbucket require a resolved token and send it as Bearer; password references are rejected. Token references omit username/password slugs. Environment-provider token references use `tokenEnvironmentVariable` instead of username/password environment variables.

This response contract must be checked against the deployed Secret Server version/template. Use a REST API Bearer access token accepted by that endpoint. Token issuance, automatic refresh, integrated Windows auth, SDK registration, approval workflows and checked-out secret lifecycles are not implemented. Backend errors disclose only fixed diagnostic text, never secret bodies or authorization headers.

References: [Delinea version-specific REST APIs](https://docs.delinea.com/online-help/secret-server-11-6-x/api-scripting/rest-api-reference-download/index.htm), [Delinea Platform access to Secret Server APIs](https://docs.delinea.com/online-help/delinea-platform/api/ss-apis.htm).

## Artifactory

`apiBaseUrl` ends at the approved Artifactory Docker API prefix, commonly `/artifactory/api/docker`. Each service supplies `containerImage: {connectionRef, repoStage, teamId, imageName}`. The repository becomes `docker-{repoStage}` and the image path becomes `{teamId}/{imageName}`. Stage is independent of the deployment environment. Legacy image sources may still supply an exact repository key and service-to-image map. Username sources may use `{username}` in registered image paths. Artifact-owner usernames are validated as 1–40 letters, digits, hyphens/underscores and are never treated as authentication credentials.

Requests use the documented Docker V2 routes:

- `GET {apiBaseUrl}/{repositoryKey}/v2/{imagePath}/tags/list?n={limit}&last={cursor}`.
- `GET {apiBaseUrl}/{repositoryKey}/v2/{imagePath}/manifests/{tag}`.

Tag pages are limited to 100 entries. A full page returns its final tag as the next cursor; a final empty page can therefore be needed. No server-provided pagination URL is followed. Ordering is the registry's lexical ordering, not publication recency. No timestamps are invented. Selection does not automatically choose or deploy the newest version. Manifest bodies are hashed and must match the returned SHA-256 digest; image-index digests remain index identities and are not incorrectly compared with platform manifests.

Reference: [JFrog List Docker Tags](https://docs.jfrog.com/artifactory/reference/listdockertags), [JFrog Docker repositories](https://docs.jfrog.com/artifactory/docs/docker-repositories).

Connections are bounded by a 5-second connect timeout, 15-second total response deadline and 2 MiB response limit. Retries, automatic bearer challenge negotiation, registry promotion, AQL, private-result caches and write APIs are not implemented. Since there is no cache, private results cannot cross cache authorization scopes. Local mode has one developer identity; team authorization is a prerequisite for exposing these read-only credentials through a shared deployment.

## Execution boundary


## Secret Server session API

- `GET /api/v1/secret-auth`: authentication mode and session status for each registered server, without credentials or tokens. Global-provider status is not a live connectivity check.
- `DELETE /api/v1/secret-auth/{connection}`: clear this browser's token for that connection. CSRF is required.

Sign-in requests cannot supply an outbound token URL; configure it through runtime settings or startup defaults. Error bodies from the token/secret servers are not returned or logged. No live authentication has been validated yet.

## Connection sessions and Bitbucket discovery

Artifactory/Bitbucket accept `token` or `secret-server`. Interactive token mode omits `credentialRef`; reference mode requires a token credential. Legacy `ad` and vault `portal` settings migrate to `token` on load.

Supplied Bearer tokens are accepted in CSRF-protected POST bodies, never URLs, configuration or exports. They are retained only in the browser-scoped server session. Artifactory/Bitbucket tokens are encrypted in memory. No AD login, Basic password authentication or OAuth password exchange is performed.

- `POST /api/v1/registry-sources/{source}/services/{service}/images/query`: `{username?, cursor?, limit?, tag?, authentication?}`. `username` is the legacy artifact-owner path selector; it is not the login username. Omit `tag` to list tags; include it to resolve a digest. Service-derived sources have the ID `service:{serviceId}`.
- `POST /api/v1/service-projects/{service}/references/query`: `{kind: "tags"|"branches", start: 0, authentication?}`. Returns up to 50 references and `nextStart`.
- `authentication` for the compatibility request-only API is `{token}`. The UI instead starts a connection session once and omits authentication from subsequent operation requests; Secret Server references also omit it.
- `POST /api/v1/secret-auth/{connection}/token`: `{token, expiresInSeconds}`. Returns status/expiry only and rotates the browser session ID.

Bitbucket service configuration is `sourceProject: {connectionRef, projectKey, repository, revision, chartPath}`. The configured connection API base ends at `/rest/api`; branch/tag discovery uses `/1.0/projects/{projectKey}/repos/{repository}/{branches|tags}`. Only the validated configured repository is queried; server-supplied URLs are never followed. The `revision` and `chartPath` are saved metadata for future checkout/Helm execution, not a claim that checkout is implemented.

Protocol references: [JFrog Basic/Bearer authentication](https://docs.jfrog.com/integrations/docs/curl-integration), [Bitbucket Data Center HTTP access tokens](https://confluence.atlassian.com/bitbucketserver100/http-access-tokens-1680278187.html). No live organization authentication has been verified.

## Session lifecycle API

- `GET /api/v1/connection-auth/{artifactory|bitbucket}/{connection}` returns mode, credential availability and expiry only. Credential availability is not a claim that the upstream has authenticated successfully.
- `POST` to that path takes `{authentication: {username, password} | {token}, lifetimeSeconds: 1800}` and rotates the browser session ID. CSRF and same-origin checks apply. Lifetime is 60–28800 seconds; the UI defaults to 30 minutes.
- `DELETE` clears that connection's credentials for this browser session.

Credentials are stored as AES-GCM ciphertext in non-serializable session entries using a process-only key. Expiry is enforced on every use; background cleanup wipes retained ciphertext within 10 seconds of expiry/configuration invalidation. Session invalidation, explicit sign-out and shutdown also wipe entries. Upstream 401 clears the corresponding connection session. This protects stored session data; a compromised running JVM can still access the process key and active requests.

An idle browser session expires after 30 minutes by default; closing a tab is not a reliable immediate logout signal. The server cannot keep authenticating after restart or resume these browser credentials in unattended workers.

Shared Loki configuration and service namespace ownership are described in [the monitoring contract](../integration/monitoring-contract.md). Loki supports both log streams and numeric LogQL results through its query API; live telemetry polling is not yet implemented.

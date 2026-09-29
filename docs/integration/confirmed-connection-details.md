# Organization connection details received

> Authentication update: the AD/password-exchange details below are historical reference only. The application now accepts supplied Bearer access tokens for Secret Server, Artifactory and Bitbucket. No AD login is supported. See [current connection configuration](../adapters/connections.md).

Updated: 2026-09-28. These details were supplied by the user; they are not evidence of a successful live connection. Hostnames are sanitized patterns, not configured or tested destinations. The entries below are integration notes, not an executable configuration schema.

## Bitbucket / Stash

Supplied credential names: `bitbucket_username` and `bitbucket_password`.

Supplied Stash API base:

```text
https://stash.domain.net/rest/api
```

Branch and tag path templates, relative to that base:

```text
1.0/projects/%s/repos/%s/branches
1.0/projects/%s/repos/%s/tags
```

Supplied authentication: `Authorization: Basic <base64(username:password)>`. The encoded value remains a credential and must not be included in configuration exports, logs or reports.

Still needed:

- Confirm whether Bitbucket and Stash refer to the same installation and credential pair, or separate systems.
- One project key and repository slug; confirm those are the two substitutions in each template.
- Sanitized branch/tag responses, including pagination fields and how to resolve the selected ref to a commit.
- The approved way to fetch deployment files: Git clone, a repository archive endpoint or a raw-file API; include a sample URL, access method and exact `ckp/` paths.
- Confirm whether the supplied credential names are Delinea field slugs, environment variable names, or logical names in existing configuration. Provide the reference/delivery mechanism, not values.

The current app has no Stash discovery or remote project-fetch adapter. Listing branches/tags alone does not retrieve or pin the chart/values content.

## Artifactory

Repository shorthand supplied by the user:

```text
helm-dev/qa/stable/release-virtual
```

Preserve this notation until the exact repository keys and types are confirmed. Do not assume it expands to `helm-dev`, `helm-qa`, `helm-stable` and `helm-release-virtual`, or infer repository type solely from the names.

Supplied tag-list URL template:

```text
https://artifactory.domain/artifactory/api/docker/${artifactoryRepo}/v2/{imagePath}/tags/list
```

The current adapter constructs:

```text
{apiBaseUrl}/{repositoryKey}/v2/{imagePath}/tags/list
```

The user confirmed this route on 2026-09-28, including the repository key and `/v2/` segment. It matches the current adapter. Repository and image path are selected through configured mappings. Artifactory uses HTTP Basic authentication with the retrieved username/password; Base64 encoding is not encryption.

Still needed:

- One fully expanded sanitized tag-list URL for one actual service/source.
- Exact repository keys and whether each stores Helm charts, Docker/OCI images, or exposes a virtual repository.
- The image pull reference for that same service and tag, plus the approved manifest/digest endpoint and response headers.
- Artifactory credential reference and exact secret field slugs; Basic authentication is now confirmed.
- Tag-list response and pagination behavior; source-to-installation-selector mapping for the pilot service.

## Delinea Secret Server

Supplied secret retrieval URL:

```text
https://passwordvault.domain.net/SecretServer/api/v1/secrets/{secretId}
```

Supplied token URL:

```text
https://passwordvault.domain.net/SecretServer/oauth2/token
```

For the existing secret-read adapter, the API base can be configured as:

```text
https://passwordvault.domain.net/SecretServer/api/v1
```

The adapter appends `/secrets/{secretId}`. It now supports explicit portal password-grant authentication with server-side session tokens, or external token delivery through environment/file references. The token endpoint is outside the API base and is configured independently. See [executable connection configuration](../adapters/connections.md). Live validation and automatic refresh are still pending.

Reference implementation reviewed: `workbook2/backend/src/main/java/com/workbook2/smtpflow/client/SecretServerClient.java`, plus `SecretAuthController` and the relevant `LogPollingService` call site. These establish code behavior, not a successful live connection or approval for an unattended application identity.

- Token acquisition uses `POST` to the independently configured token URL, with `Content-Type: application/x-www-form-urlencoded` and fields `grant_type=password`, `username`, and `password`. The callers identify these as AD credentials. This method sends no client ID, client secret or scope.
- The client accepts token fields `access_token`, `token`, or `accessToken`; token type `token_type` or `tokenType` (default Bearer); expiry `expires_in` or `expiresIn`. These are accepted parser variants, not a captured server response.
- Secret retrieval uses `GET` with the token in the Authorization header. A supplied access token is also supported. With no token URL, the reference client has a Basic-auth fallback; we should not enable that implicitly in this app.
- Retrieval accepts a secret URL template or appends the secret ID. It supports `items[].slug` / `fieldName` and `itemValue` / `value`, as well as recursive username/password aliases. Our app should retain explicit field-slug mapping instead of guessing which field contains a credential.
- The reference controller returns a token and expiry to its caller and defaults missing/nonpositive expiry to one hour. That default is not evidence of the server's token lifetime. This app's planned token acquisition should keep credentials/tokens on the backend and use a confirmed expiry/reacquisition contract.

Still needed:

- User confirmed two modes: portal authentication now, with a global/tenant token potentially supplied by a CKP init container later. Confirm the eventual tenant identity, authorization mapping and background-run credential lifetime.
- Portal users provide their AD credentials for token exchange. Future init-container/global-token provisioning and renewal details are still needed; do not reuse Bitbucket credentials implicitly.
- A sanitized token response to confirm the accepted field variant and actual expiry, plus refresh/reacquisition rules.
- One secret ID/reference, sanitized retrieval response, and exact field slugs for each destination's credentials.
- Any unattended-access, approval or secret checkout/check-in requirements.

The example connection file now demonstrates the `/SecretServer/api/v1` path using its existing non-routable example host. No real credentials or endpoints have been enabled.

## Loki logs

Supplied environment-dependent base:

```text
https://logs.{env}-domain.net/loki/api/v1
```

This identifies Loki for the supplied log integration. It does not establish the backend used for metrics.

Reference implementation reviewed: `workbook2/backend/src/main/java/com/workbook2/smtpflow/client/GrafanaClient.java`, its `GrafanaConfig` model, and the relevant `LogPollingService` call site.

- Requests go directly to `{baseUrl}/query_range` (or use the base unchanged if it already ends in `/query_range`). Query parameters are URL-encoded `query`, `start` and `end` as epoch seconds, and a fixed `limit=500`.
- Authentication is Basic using a username/password retrieved from Delinea. The polling service maps each namespace to a secret ID, retrieves that secret, and queries that namespace with its own credentials. These credentials are separate from the initial AD credentials and Delinea token.
- Configured LogQL templates substitute `{{correlationId}}` / `{{mtid}}`, `{{cluster}}` / `{{clusterEnv}}`, and `{{namespace}}`. An override can replace the configured cluster. We still need the actual approved template and an equivalent run/traffic identifier for this application.
- The client parses `data.result[].values[]` timestamp/line pairs. It does not implement a metrics API. No tenant header is sent in this client; confirm whether the pilot needs one.
- The reference polling service combines results across namespace credentials. The orchestrator will need explicit partial-failure and truncation reporting rather than interpreting unavailable logs as no traffic.

Implementation differences to retain when adapting: preserve password whitespace, use explicit secret fields, bound request time and response size, avoid logging raw error response bodies, and validate/escape template variables for their LogQL context. The reference client's raw substitution and fixed 500-line cap should not become assumptions about complete run evidence.

Still needed for logs:

- Approved environment-to-host mappings; whether environment IDs need an explicit hostname alias.
- Namespace-to-secret-reference mappings and exact credential field slugs for the pilot; confirm Basic authentication applies to its Loki host and identify any required tenant/header names.
- The user supplied the per-message LogQL template and clarified that performance metrics use a selected datasource and namespace, without requiring MTID. See [monitoring contract](monitoring-contract.md) for both queries and the remaining datasource/response details.
- Polling interval, maximum lines/bytes/range, redaction rules and permission scope.

The initial `message_total` PromQL query has been supplied. Still needed for metrics: datasource/API identity, response shapes, credential references, environment mappings and outcome meanings. Grafana dashboard URLs can be supplied separately. No monitoring requests were made from these notes.

## Most useful next examples

1. One sanitized token/secret response with exact field slugs and the pilot credential references; the portal authentication flow has been selected.
2. One expanded Artifactory tag-list URL, its repository type, authentication reference and corresponding image pull reference.
3. One Stash project/repository with the approved clone/archive/raw-file URL and the credential-name mapping.

After these are confirmed, work can proceed on the specific token, discovery and project-source adapters. A concrete Helm load-generator command and repository-backed YAML profile workflow have now been supplied; see [deployment and load contract](deployment-load-contract.md). Readiness, stop, result and execution-authorization details are still needed before enabling real execution.

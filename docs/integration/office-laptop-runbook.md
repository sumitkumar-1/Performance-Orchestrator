> For opt-in real deployment and timed load execution, use [Real execution](real-execution.md) after the connection checks below. Historical statements below about missing execution adapters describe the earlier read-only pilot.

# Office laptop: real integration and E2E preparation

Use this guide with the exact application build you transfer to work. Start with one service, one Docker repository and one Secret Server credential. Complete each checkpoint before adding another integration. No Codex installation is needed on the office laptop.

## 1. Know what this build can test

| Integration | Available now | Successful checkpoint |
| --- | --- | --- |
| Delinea portal authentication | Yes | Browser shows signed-in status and expiry |
| Delinea secret retrieval | Yes, on demand | Registry discovery gets past credential resolution |
| Artifactory Docker tags and manifests | Yes, read-only | A known tag is listed and resolves to a SHA-256 digest |
| Git/Bitbucket/Stash checkout | Not implemented | Requires a new adapter and the source contract below |
| Real Helm deployment and readiness | Not implemented | Requires execution, authorization and readiness adapters |
| Real load start/status/stop/results | Not implemented | Requires a load-generator adapter |
| Grafana metrics and Loki logs | Not implemented | Requires telemetry adapters and datasource/query contracts |


## 2. Prepare the office handoff

You confirmed Java 21 and Maven are installed on the office laptop, so use the source-build route below as your normal workflow. The packaged-JAR option is a fallback only.

Transfer using your organization's approved method. Keep the code/build separate from office configuration so subsequent updates do not overwrite your settings.

- Transfer the source project and this guide if office builds are allowed. You need JDK 21+ and Maven 3.6.3+ with your approved dependency mirror. Run `java -version`, `mvn -version`, then `mvn -B verify` from the project directory. Both Java and Maven must use Java 21+.
- Alternatively, transfer the tested `target/perf-orchestrator-0.1.0.jar`, this guide, the starter configuration and support template. Running the packaged JAR needs Java 21+, but does not require Maven, Node, Docker, Helm or kubectl. Keep the JAR under `target/` to use the commands below unchanged.
- Do not transfer personal `data/`, `.local/`, credentials or logs with the code. Create fresh office state. Keep office `.local/` and `data/` when replacing a build; back them up while the app is stopped. They are already ignored by Git.
- Record the JAR SHA-256 on both machines. This identifies the actual build even when the filename remains `0.1.0`.

macOS/Linux:

```sh
shasum -a 256 target/perf-orchestrator-0.1.0.jar
# On Linux without shasum, use sha256sum instead.
```

Windows PowerShell:

```powershell
Get-FileHash target/perf-orchestrator-0.1.0.jar -Algorithm SHA256
```

Do not skip failing build tests. For dependency download failures, configure the organization's approved Maven mirror/proxy; offline Maven works only after all required dependencies and plugins are cached.

## 3. Collect the minimum first-pilot configuration

Ask the vault and registry owners for these values. Actual passwords/tokens stay inside your office environment.

| Value | What to verify |
| --- | --- |
| Secret Server API base | For example `https://passwordvault.DOMAIN/SecretServer/api/v1`; omit `/secrets/{id}` here |
| Access token | Generate a REST API Bearer token in the respective service portal |
| Supported login flow | Paste a supplied Bearer access token into the session dialog; no AD login or token exchange |
| Secret ID | Numeric ID of a secret your account may read; no approval/checkout workflow supported yet |
| Field slugs | Exact `items[].slug` names for registry token, not display labels |
| Artifactory API base | Usually `https://artifactory.DOMAIN/artifactory/api/docker` |
| Docker repository key | Exact repository containing the image, not an assumed Helm repository name |
| Image path | Path within that repository, without tag or repository prefix |
| Pull repository | The actual image pull hostname/path; it may differ from the REST API hostname |
| Known tag | One existing non-`latest` tag and, if available, its expected manifest/index digest |
| Network/trust | VPN, DNS, approved proxy and private CA trust for the Java process |

Your `helm-dev/qa/stable/release-virtual` names need confirmation: chart repositories and Docker image repositories serve different purposes. Obtain one fully expanded, known-working tag-list URL with credentials removed. This build constructs:

```text
{apiBaseUrl}/{repositoryKey}/v2/{imagePath}/tags/list?n=50
{apiBaseUrl}/{repositoryKey}/v2/{imagePath}/manifests/{tag}
```

On 2026-09-28 you confirmed this repository + `/v2/` + image-path route ; authentication now uses a supplied Bearer token. Supply the actual repository/image mappings and credential references for the pilot.

## 4. Create a minimal office configuration

Copy [office-pilot-connections.yaml](examples/office-pilot-connections.yaml) to `.local/connections.yaml`. Create `.local/` first. Replace every placeholder using the previous checklist, including `secretId`, field slugs and the pull hostname. Keep all four top-level maps.

The relationship is:

```text
office-dev image source → office Artifactory connection → registry-reader credential
registry-reader → office-vault Secret Server connection + secret ID + token field slug
```

Supply a Secret Server Bearer token in its sign-in dialog. The registry can use its own supplied token or a token resolved from a vault secret. These are separate credentials; never put token values in YAML.

For this read-only test, the service key under `imagePaths` is sufficient. You do not need to configure a deployment environment, chart, namespace, release or load scenario yet. Adding those catalog entries cannot enable real execution.

## 5. Start and verify the application

Open http://127.0.0.1:8080. The app starts with configured integrations and no seeded runs. Enable `orchestrator.execution.enabled` and configure approved cluster targets before using run review/deployment. Follow [the execution guide](real-execution.md) after validating connections.

Run from the same project/installation directory each time: default state paths are relative to the working directory. Stop any previous app using Ctrl-C in its terminal first.

macOS/Linux with the source scripts:

```sh
./scripts/run-local.sh run -- --orchestrator.connections=.local/connections.yaml
```

Direct Java, also suitable for Windows PowerShell:

```sh
java -jar target/perf-orchestrator-0.1.0.jar --orchestrator.connections=.local/connections.yaml
```



Java uses its trust configuration, which may differ from the browser's. A successful browser visit does not prove Java can connect. Have IT configure approved CA trust/proxy settings for this Java runtime. Do not disable TLS verification; do not assume `HTTP_PROXY` alone configures the application. Outbound requests require HTTPS and do not follow redirects.

## 6. Authenticate and discover one image

1. Open **Connections & catalog**. Under **Secret Server: office-vault**, sign in using your approved vault identity.
2. Confirm signed-in status/expiry. This proves token exchange only; the secret has not necessarily been read yet.
3. Select `office-dev` and the configured service. Click **Discover versions**. This retrieves the secret and sends its registry credentials to Artifactory.
4. Find your known tag. Use the next page if necessary; tags are not ordered by build time. Do not assume the first tag is newest.
5. Select that tag and click **Resolve digest**. Record the tag, returned image reference and SHA-256 digest. Confirm the pull hostname/path is correct. This does not download the image or test cluster image-pull permissions.
6. If comparing a digest from another tool, compare the same manifest/index representation. A multi-platform index digest and a platform-specific manifest digest can legitimately differ.

Checkpoint: one known tag resolves successfully. Record the timestamp and non-sensitive outcome in the [support report](support-report-template.md). Then repeat with a second repository or second service, changing only one mapping at a time.

## 7. Update configuration without losing state

The startup YAML is a bootstrap file. Once settings are saved through the UI, `data/real/configuration.json` takes precedence, including after restart. Resource files are not watched.

Use the forms in **Connections & catalog** for subsequent changes. Configure Secret Servers, then credential references, then Artifactory/Bitbucket connections and image/source mappings under each service. Shared Loki references, service monitoring credentials and service destinations also have editing forms. Each save validates the complete configuration, including references. Reauthenticate with **Secret Servers → Sign in** after changing connections. Use **Connection diagnostics → Browse versions** to test registry access, and **Browse Git references** for Bitbucket. Choose AD, token or a credential reference on each connection; direct AD/token inputs start an expiring connection session shared by services using that connection. Use the Session button to inspect expiry or sign out. Inputs are cleared after submission.

For bulk changes or backups, expand **Advanced JSON · import, export & restore**. The selector exposes separate configuration maps; paste only the selected map as JSON, without its outer section name. Do not paste Spring YAML. Import loads a complete export into the editor for review before saving.

Checkpoint: changes apply immediately and survive restart. A conflict means another edit used a newer revision; reload and reapply your change. A rejected save leaves the previous configuration active.

To test different bootstrap files independently, supply a new, unused configuration path, for example `--orchestrator.configuration-file=.local/pilot-b-runtime.json`. Keep using that path when restarting that pilot. This isolates settings only, not the database. Do not delete the database merely to update connections, and keep the database path stable across restarts.

After an application update, stop the old process, back up office state, replace the JAR and restart with the same arguments. Record the new checksum. Database migrations can affect downgrade compatibility; retain the stopped-state backup with the previous JAR instead of assuming an older build can open newer data.

## 8. Diagnose the failed checkpoint

| Symptom | First checks / useful evidence |
| --- | --- |
| App fails before UI starts | Java version, full sanitized startup exception, working directory, config path, YAML indentation, writable state directory and free port |
| Edited YAML seems ignored | Saved runtime override takes precedence; use the runtime editor or a new explicit configuration path |
| Configuration rejected | Copy the error code/field/message; check unknown fields, cross-references, HTTPS URL shape, numeric secret ID and exact JSON section |
| `SECRET_AUTH_FAILED` | Confirm password-grant support, account format, token URL and account policy with vault owner; do not send passwords here |
| `SECRET_TOKEN_SCHEMA` | Owner should verify standard `access_token`, Bearer token type and positive `expires_in`; share only field names/types |
| `SECRET_SIGN_IN_REQUIRED` | Sign in again after expiry, app restart or connection change; do not expect browser-session credentials to authorize future workers |
| `SECRET_SCHEMA` | Verify selected ID and exact username/password slugs in `items[]`; share redacted response structure only |
| `SOURCE_DENIED` | Determine whether vault read or registry request was denied; check permission to the specific secret/repository and Basic-auth support |
| `SOURCE_NOT_FOUND` | Check API base, numeric ID, repository key and image path against a known-working request |
| `SOURCE_UNAVAILABLE` | Check Java CA trust, VPN/DNS/proxy, redirect, remote health, latency and response size. Errors intentionally omit upstream bodies; this code alone cannot distinguish these causes |
| Empty tag list | Check image path/repository and reader access; confirm an existing tag independently with the repository owner |
| Manifest/digest error | Capture code/field/message and the expected representation; confirm tag exists, is not `latest`, and digest header matches manifest bytes |
| Mutation rejected after refresh/restart | Reload UI to obtain a fresh local session/CSRF state; use the same `127.0.0.1` origin and port |

Current outbound limits are 5 seconds to connect, 15 seconds for the total response and 2 MiB per response; no automatic retries. Avoid retrying a rejected login repeatedly: consult the vault owner about account policy. Never enable broad HTTP wire logging to collect credentials. Use the browser UI for authenticated requests; manual API mutations additionally need the session cookie and CSRF header.

## 9. Prepare the next implementation batch

The [deployment and load contract](deployment-load-contract.md) now records your Helm command and repository-backed YAML profile workflow. Use it alongside the remaining details below.

After the read-only checkpoint, collect one representative service's complete contract. Keep sanitized examples in one report so the next code update can include contract tests before you transfer it.

| Batch | Organization input required | What needs implementation and acceptance testing |
| --- | --- | --- |
| Source and deployment inputs | Stash base URL, project/repo, branch/tag or commit, exact chart directory, chart dependencies, values file location and secret-field slugs such as `bitbucket_username` / `bitbucket_password` | Authenticated source retrieval pinned to an immutable revision; verify downloaded content and selected chart |
| Environment binding | Dev/perf cluster context, namespace, release, execution identity, approved Helm version/command and image override keys | Explicit environment mapping, render/diff validation, credential scope, readiness checks, failure reporting and reconciliation |
| Deployment acceptance | One owner-approved manual deployment example, expected workload/health checks, timeout and cleanup/rollback policy | Deploy a single service and verify the expected immutable image; test failed readiness and recovery |
| Load control | Exact tool/API/command, workload file, target, allowed rate/concurrency/duration, start/status/stop examples, result schema and ownership | Bounded execution, cancellation, timeout, idempotency and results; start with a small approved dev test |
| Metrics and logs | Grafana datasource UID/type or direct query endpoint, authentication refs per namespace, sample response, query, time window and thresholds | Metrics/Loki adapters, result parsing and live evidence; distinguish absence of data from a passing result |
| Background credentials | Approved unattended identity/token provisioning, rotation and authorization rules | Worker credential access; interactive portal sessions alone are insufficient for unattended execution |

Environment means where the workload runs. Image source means where its image is read. Keep these independent: a dev repository does not automatically select a dev namespace. Environment entries bind the cluster and shared Loki source; service entries own namespace/release overrides and per-environment credentials; do not encode the environment implicitly in a URL.

For traffic, Loki/LogQL is now confirmed. Log streams and log-derived rate/count queries use the same configured Loki base and service/environment credential reference. The earlier `message_total{...}` expression was PromQL and must not be sent unchanged to Loki. Supply a working generator and downstream LogQL query and its event semantics. Log-event rate is a message rate only when matching events correspond one-to-one with messages. Namespace-wide results can include unrelated traffic.

Use the [organization questionnaire](organization-questionnaire.md) for deeper details and the [monitoring contract](monitoring-contract.md) for query design. Configurable secret references exist now, but arbitrary fields such as namespace are not automatically fetched from Secret Server. Specify which deployment/monitoring fields need resolution so that support can be implemented explicitly.

## 10. E2E acceptance sequence after the adapters are delivered

This is the future test plan, not a currently executable workflow:

1. Repeat the read-only smoke test using the new build and saved configuration.
2. Retrieve one pinned source revision and render its chart with the selected image and environment values; inspect namespace, release and image before deploying.
3. Deploy to an approved dev namespace; confirm workload readiness and the selected image identity without sending load.
4. Run the smallest approved workload; verify status, explicit stop and timeout handling before increasing traffic.
5. Compare timestamps, outcome counts/rates and errors with the organization's existing dashboard and load-generator results.
6. Test a failed deployment, failed load run, token expiry, unavailable monitoring source and app restart; confirm no duplicate workload and no false success.
7. Verify report evidence, release/namespace ownership, cleanup policy and preservation of unrelated resources.
8. Repeat in perf with explicit environment mapping and an owner-approved load ceiling.

Deploying the orchestrator itself into CKP is optional and separate from deploying target services. Start on the laptop to keep diagnosis simpler. When moving the application to CKP, follow the [README packaging steps](../../README.md#ckp-packaging-and-deployment), provide cluster CA/network access and persistent storage, and use port-forwarding. Service/Ingress shared access is intentionally disabled until shared-user authentication exists. Laptop connectivity does not prove pod connectivity. No live CKP deployment has been validated yet.

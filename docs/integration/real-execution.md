# Real execution pilot

Real mode now supports saved real profiles, CKP sparse checkout, pinned preparation, Helm deployment, timed load runs, cancellation, owned-load cleanup, optional LogQL measurements, run history and reports. Simulation remains separate. No organization cluster was contacted during development; perform the first run in sandbox.

## 1. Local prerequisites and cluster binding

On the machine running the application, install Java 21+, Maven, Git supporting sparse checkout/partial clone, Helm 3, and kubectl compatible with the target cluster. Authenticate kubectl as usual. Java, Git and the Kubernetes clients must each trust the organization's CA; do not disable TLS verification.

In `application.yaml`, configure:

```yaml
orchestrator:
  mode: real
  target-environment: sandbox
  execution:
    enabled: true
    kube-context: sandbox-nvan
    expected-api-server: https://YOUR-SANDBOX-KUBERNETES-API
    workspace: data/real/workspaces
    command-timeout-seconds: 300
```

Find the exact API URL with this read-only command:

```sh
kubectl --context sandbox-nvan config view --minify -o 'jsonpath={.clusters[0].cluster.server}'
```

The application explicitly passes the configured context to every Helm/kubectl command and compares its API URL before mutations. It does not run `kubectl config use-context`. Namespaces and the required RBAC must already exist. Test the same identity's chart deployment permissions before attempting a run.

Rebuild and start:

```sh
./scripts/run-local.sh build-run --mode real
```

The cluster binding is startup-only. Editable service/connection settings remain in Connections & catalog, with dashboard overrides retaining their existing precedence.

## 2. Service catalog and authentication

Each service, including the load generator, needs:

- A Bitbucket connection, project key, repository slug, selected revision and a chart path below `ckp/`.
- Artifactory connection, repository stage, team and image name.
- Deployment namespace, release name and values-file paths below `ckp/`.
- Dependencies, if service deployment order matters.

Bitbucket repository metadata is read at `GET {apiBaseUrl}/1.0/projects/{projectKey}/repos/{repository}`. Its `links.clone` HTTPS URL is used; `sourceProject.cloneUrl` is an optional override. The clone authority must match the configured Bitbucket authority. Cross-host cloning requires a separate reviewed connection, rather than forwarding a token automatically.

Source preparation uses Git fetch with `--depth=1 --filter=blob:none` and non-cone sparse checkout restricted to `/ckp/`. The server can ignore partial-clone filtering, so this guarantees a CKP-only working tree, not a guarantee of minimal network transfer. The selected revision resolves to a commit and only that snapshot is used. Symlinks, paths outside CKP, snapshots above 16 MiB/2000 files and missing charts are rejected. Before lint/render, charts with declared dependencies run `helm dependency build` when Chart.lock (or legacy requirements.lock) exists, otherwise `helm dependency update`. Downloads and the lock file become part of the reviewed snapshot, subject to the same 16 MiB/2000-file limits. Deployment uses this snapshot without resolving dependencies again. Git submodules are not fetched.

Before lint/render, preparation replaces `__REPLACEAPPVERSION__` in the `version` and `appVersion` fields of the selected chart's `Chart.yaml` and unpacked child charts. `appVersion` gets the exact selected image tag, without the digest. Chart `version` must be SemVer: `1.11.0` stays unchanged; `1.11.0.260811-12-4309-05-791d17a0a4bf` becomes `1.11.0-build.260811-12-4309-05-791d17a0a4bf`. A leading `v` is removed for SemVer tags. Other image tags use `0.0.0-build.<tag>`, with dots/underscores converted to hyphens and a `tag-` prefix for leading-zero numeric identifiers. Explicit metadata versions are preserved. Packaged `.tgz` dependencies must already have valid metadata. Only the prepared snapshot changes, not Git; original and prepared file hashes are retained, and deployment uses the same prepared files that were validated. YAML comments in changed metadata files are not retained. When reproducing lint locally, perform this metadata replacement first.

Use token sessions or Secret Server token references for Git/Artifactory. Sign in before fetching files/preparing. Git authorization is supplied through child-process environment settings, never command arguments, URLs, Git config files or logs. Chart output is withheld on failure because manifests can contain secrets. Validation errors identify the service, chart, namespace, selected values files, failed operation, exit code, a recognized diagnostic category and a source-file line when available. Lint receives the selected namespace. Inspect an approved local checkout with Helm directly if chart lint/render errors need deeper investigation.

## 3. Prepare a profile

Open Overview → Configure run.

Review performs cluster/release checks, sparse CKP checkout, image resolution, Helm dependency downloads and chart lint/render. Service preparations run concurrently, bounded across reviews by `orchestrator.execution.preparation-concurrency` (default `3`, range `1–8`; set `1` for sequential preparation). CKP values use `application.orchestrator.execution.preparation-concurrency`. Restart to change this setting. Deployment itself remains sequential in the selected order. Fresh checkouts and dependency snapshots are retained for correctness; no persistent credential or mutable-chart cache is introduced.

The review progress card shows each service's stage and elapsed time, plus completion/failure icons and an animation respecting reduced-motion preferences. It works independently of diagnostic recording. Progress is temporary, scoped to the browser session's latest review, and contains no command output or credentials. A failed review cancels outstanding preparation work and cleans up temporary workspaces before returning the error.

1. Create or load a saved profile.
2. Click **Add service** to configure a deployment in a dialog. Drag the service handles or use the up/down buttons to set deployment order. Services deploy top to bottom, with readiness checked before the next service. This order is saved with the run profile and frozen during review; changing it requires another review. Legacy catalog dependencies no longer control ordering; real and simulation runs both follow the saved profile’s service order. Choose the load generator in its own dialog; it cannot also be a service deployment and starts after all services are ready.
3. Git branches/tags and all image-version pages load automatically. The selector contains branches and tags only. On editing a profile it restores the named reference when available; otherwise it prefers `master`, then the configured revision or an available reference. There is no Saved commit option. Exact commits remain pinned internally for the prepared deployment. The image dropdown includes release tags and development hashes, with a filter for long lists.
4. CKP `values*.yaml` / `values*.yml` files load automatically from the selected Git revision. The environment values file is selected when present. Select one or more files; the editor immediately displays the selected YAML. For multiple files, the numbered editor selector shows merge order; use **Apply earlier** to reorder. Later files take precedence.
5. Edit YAML if needed. Edits replace that file's contents for preparation and are saved with the run profile; repository files are untouched. **Restore file** discards edits to the displayed file. Helm still applies chart defaults; use YAML null where Helm requires removal of a default key. Older profiles retain their additional overlay under **Existing profile overlay**.
6. Set measurement duration: the observation window after warmup. **Advanced timing** contains warmup and the overall timeout, which includes deployment. On timeout, cleanup starts; in-flight commands and cleanup can take additional time. The load tool's YAML duration should cover warmup plus measurement.
7. Add monitoring panels if needed. In a metric panel, optionally enable **Pass/fail check** and enter its maximum acceptable value; no separate threshold JSON section is needed. Then click **Review run**. Review the prepared values and target, confirm, and choose **Start run**.

Service namespace/release settings come from Services, including environment-specific destination exceptions. Preparation does not install charts. It fetches and snapshots CKP, resolves image digests, renders/lints charts and reads Helm release baselines. Plans expire after 15 minutes. Configuration changes, changed image tags or baseline drift require a new plan.

For every service and load generator, `imageName`, `imageTag`, `global.imageTag` and `global.environment` are managed. Both `targetPlatform` and `global.targetPlatform` are always `ckp`. Both `clusterSubdomain` and `global.clusterSubdomain` default to the bound environment’s `clusterIdentity` (configure the CKP subdomain without a URL scheme). `global.deploymentSuffix` defaults to an empty string and `tags.moc` defaults to boolean `false`. Selected YAML/editor values and legacy overlays can override these subdomain, suffix and mock defaults; platform and image/environment selections remain enforced. Both image-tag values exactly match the selected version, including dated/hash versions; no digest is appended. The rendered chart must reference the selected image name and exact tag. The resolved registry digest remains in the plan and is checked again at submission. Kubernetes pulls by tag, so tags must remain immutable for reproducibility; this does not guarantee a digest-pinned runtime image. All other values—including rate, destinations and the load tool's own duration—come from the selected YAML/overlay. The orchestrator does not guess those field names. No values are silently rewritten to simulated virtual-user or request-rate fields.

Use Kubernetes Secret references in values. Prepared chart snapshots and values are stored in the application database for repeatability; do not paste credentials into a profile. Git/Artifactory tokens are not persisted with the plan.

## 4. Execution and stop behavior

Timeouts are configured at startup in `application.yaml`:

```yaml
orchestrator:
  execution:
    command-timeout-seconds: 600
    default-run-duration-seconds: 7200
    max-run-duration-seconds: 28800
```

`command-timeout-seconds` controls each Helm operation (10–1800 seconds); subprocesses have an additional 15-second allowance. `default-run-duration-seconds` initializes new run profiles. `max-run-duration-seconds` caps the overall timeout accepted at review. The overall timeout must be at least 60 seconds, and the default must fit within the configured maximum. Under Helm values, put these settings under `application.orchestrator.execution`. Restart after changing them.

Saved profiles retain their previous overall timeout: edit **Advanced timing → Overall timeout (seconds)**, save and review again. Seven deployments at up to 600 seconds each can consume 4,200 seconds before load installation, warmup or measurement, so budget the whole run accordingly. The overall deadline is checked between operations; it does not forcibly interrupt an in-progress Helm command, and cleanup can extend past it.

Run status and the execution timeline identify each service as deployment starts, including its position, namespace and release. The current deployment shows a live elapsed timer; completion events retain the time until Helm readiness, and load-generator installation is tracked separately. These events persist independently of optional command diagnostics.

Services use `helm upgrade --install --wait`. The application does not uninstall existing service releases before deployment: that can delete release-managed resources and disrupt persistent workloads. Helm updates chart-managed ConfigMaps during upgrade; application reload or pod restart behavior depends on the chart (for example, configuration checksum annotations). Only the run-owned load release is uninstalled during cleanup.

- Service charts run `helm upgrade --install --wait`. Service releases remain after the run; there is no automatic rollback.
- The load release must be absent. Load uses `helm install`, never upgrades/claims an existing release. Select a dedicated namespace/release and prevent concurrent external operators from managing it during a run.
- Load timing starts after Helm installation returns. Helm installation does not prove that traffic is flowing; use LogQL evidence for that. Hooks and the tool's natural completion are chart-defined. This pilot uses the configured measurement window rather than inferring natural completion from pod termination.
- After the measurement window, metrics are collected and the owned load release is uninstalled with `--wait`. Cancellation uses the same cleanup path. An in-flight CLI command is bounded by the command timeout; cancellation is checked between commands.
- The run ID is recorded in the Helm release description. Cleanup refuses a release whose ownership marker changed. Failure keeps the environment reserved as NEEDS_ATTENTION. Verify cleanup & release retries this check; it never bypasses ownership.
- Idempotency keys prevent duplicate run submission. One active run per instance environment is enforced. Multi-instance execution against the same cluster is not supported; use one replica and dedicated releases.
- After restart, an interrupted real run attempts owned-load cleanup and ends failed/inconclusive rather than replaying deployments. Monitoring credentials are not recovered from disk. Leave execution enabled and the same cluster binding available for recovery. If the application cannot run, an operator must stop the load externally; this application cannot enforce a deadline while offline.

## 5. LogQL measurements

Use **Monitoring → Add panel** in Configure run. Each panel has a title, service, display type (logs or metric), editable Loki namespace, LogQL query and optional credential-reference override. Defaults come from the service's namespace and environment-specific credential. Multiple services/namespaces can share one environment Loki endpoint with distinct secrets. The panel ID identifies end-of-run metric thresholds. The equivalent profile JSON is:

```json
[
  {
    "serviceId": "ps-spoolers-ps-load-gen",
    "name": "request_count",
    "query": "sum(count_over_time({cluster_env=\"{{clusterEnv}}\", namespace=\"{{namespace}}\"} |= \"REPLACE_WITH_GENERATED_MESSAGE_EVENT\" [{{durationSeconds}}s]))"
  }
]
```

This is an example, not an approved organization query. Supply actual generator and downstream queries. Each must return exactly one aggregated numeric vector sample from `/query`. Multiple series, empty results, non-finite values, authorization failures and malformed responses remain unavailable. `{{namespace}}`, `{{clusterEnv}}` (also `{{clustEnv}}`) and `{{durationSeconds}}` are supported. A log count equals a message count only if events correspond one-to-one and ingestion is complete. No custom tenant header is implemented yet.

The verdict adapter collects metric panels at the end of the measurement window using its end timestamp; log panels do not contribute numeric verdict evidence. Ingestion lag and query window semantics must be accounted for in the approved queries. Monitoring credentials are resolved at submission and retained in memory only for the active run, then discarded; expired upstream credentials produce unavailable evidence. No background Secret Server password login or token renewal occurs.

A positive `request_count` and configured thresholds are required for a PASS. Thresholds currently express **maximum** values, suitable for error ratios/latency, not minimum throughput. Example:

```json
[{"metric":"error_rate","maximum":0.01,"required":true}]
```

Without approved measurements/thresholds, execution can complete but performance is INCONCLUSIVE. No synthetic numbers are generated in real mode.

## 6. CKP deployment

The Dockerfile includes Git, Helm and kubectl; select organization-approved base images and tool versions through build arguments. Downloaded Helm/kubectl archives are checksum-verified. This Dockerfile assumes a Debian/Ubuntu-compatible base; adapt it for your internal base if necessary. The container build was not tested against the office registry.

Helm values:

```yaml
mode: real
targetEnvironment: sandbox
serviceAccount:
  name: YOUR_APPROVED_ORCHESTRATOR_SERVICE_ACCOUNT
  automountToken: true
application:
  orchestrator:
    execution:
      enabled: true
      kube-context: in-cluster
      expected-api-server: https://kubernetes.default.svc
      workspace: /app/data/real/workspaces
      command-timeout-seconds: 300
```

The account must already exist with approved access to each service/load namespace and the resources used by their charts, including Helm release storage. This chart does not grant blanket cluster-admin. In-cluster kubeconfig refers to the mounted service-account `tokenFile`/CA so token rotation can be consumed without copying token values into configuration. The app remains loopback-only; use the existing port-forward workflow.

## Protocol references

- [Bitbucket Server repository API and HTTPS clone links](https://docs.atlassian.com/bitbucket-server/rest/7.21.0/bitbucket-rest.html)
- [Git sparse checkout](https://git-scm.com/docs/git-sparse-checkout)
- [Helm upgrade](https://docs.helm.sh/docs/helm/helm_upgrade/)
- [Loki HTTP API](https://grafana.com/docs/loki/latest/reference/loki-http-api/)

## Secret Server AD or token sign-in

Configure the vault with `authMode: interactive` and `tokenUrl: https://domain/SecretServer/oauth2/token`. `apiBaseUrl` remains `https://domain/SecretServer/api/v1`; both endpoints must use the same authority. Application and CKP defaults now use this mode. Existing saved dashboard overrides retain precedence: edit the vault and choose **AD sign-in or access token** if it still shows token-only mode.

The initial prompt offers AD username/password or a supplied token. AD uses the password grant, discards the password after the request, retains only the token and username in the server session, and honors the returned expiry (capped at 8 hours). Sign-out, expiry and connection changes invalidate the identity. Your vault must permit this grant; MFA/browser-only policies may require the token option. Token-only login does not verify a username. AD usernames are captured in prepared plans and existing action audit records, including configuration updates. This remains integration authentication for a loopback application, not shared-user role authorization.

[Secret Server password-grant documentation](https://docs.delinea.com/online-help/secret-server-11-6-x/api-scripting/authenticating/index.htm)

## Live monitoring

Open a run and choose **Open monitoring**. Panels query Loki's `/query_range` endpoint with their own mapped credentials, using your current session. They show log entries or metric trends and refresh every 15 seconds while enabled. Start/end times are editable in local time; requests use UTC. The initial window starts at run start and spans `generator.runTime` (or `generator.runTIme`) from the effective load YAML, otherwise 15 minutes. Ranges are capped at 7 days; this does not change execution duration or its 8-hour maximum.

Add, edit or remove panels during/after a run, then **Save monitoring panels**. These changes persist for that run only and do not change its frozen verdict queries. Update a saved run profile to reuse new panels in future runs. No credentials are stored with panel definitions. Reauthenticate if your vault session expires. Queries use the service's current environment credential unless a panel selects an existing reference explicitly. Set the LogQL namespace using `{{namespace}}` to use the panel's namespace input; a hard-coded query namespace takes precedence within that query.

Queries are bounded to 500 log entries, approximately 1000 time samples per series and a 2 MiB upstream response. The UI plots up to 20 metric series and provides sample values. Each panel reports failures independently; missing data is not treated as zero. There is no custom Loki tenant-header support yet.

[Loki range-query protocol](https://grafana.com/docs/loki/latest/reference/loki-http-api/)

## Service settings and version ordering

Dependencies require those services to be selected and deployed first. The allowed-values list is a simulation allowlist and is hidden in real mode; real values YAML remains editable. Image versions put stable `major.minor.patch` releases first, in ascending numeric order, then development builds ordered newest first by their `YYMMDD` segment (for example, `1.11.0.260811-...` before `1.11.0.260810-...`). Tags without a valid date follow dated builds; equal-date and undated tags use natural sorting. Run-configuration errors now appear in a sticky alert at the top and receive focus.

Pass/fail rules live in each metric panel in Configure run. Existing saved threshold rules are restored into those controls. Log panels have no numeric checks. Editing a live dashboard does not change the already-prepared verdict rules. When reopening a service selects a newer Git commit, the dialog calls out the refreshed source; review any retained YAML edits before saving.

## Reusing monitoring panels

In Configure run or a run's monitoring dashboard, use **Monitoring → Save as new set** to give the current panels a reusable name. On a future run, select that name under **Saved monitoring sets** and click **Load set**. **Update saved set** replaces its saved definition; **Delete set** removes only the reusable definition, leaving existing runs/profiles unchanged. Loading a set asks before replacing nonempty current panels.

Sets are stored in the application database, scoped to the instance's target environment, and survive application restarts while that database is retained. They store LogQL, service, namespace, credential references and optional evaluation rules; they do not store resolved credentials. Configure run saves evaluation rules with the set. Live dashboards cannot change final verdict rules; when updating an existing set from a live dashboard, rules for unchanged metric IDs are retained. A new set saved from a live dashboard contains panels only.

Adding or editing a panel updates the current form. **Save profile** also persists panels with the full run profile. **Save monitoring panels** on a live dashboard persists that run's dashboard only; use **Save as new set** as well to reuse it for later runs. Sets are separate from Connections & catalog configuration JSON exports; keep the application database to retain them.

## Troubleshooting Review run / Helm lint

A lint failure happens during preparation, before this run deploys anything. “Dependencies must be vendored” in older error messages was a general requirement, not evidence that dependencies caused the failure. Updated errors distinguish recognized dependency, schema, missing-value and template/YAML errors without returning raw output. Classification is a hint; it cannot replace the full local Helm diagnostic.

On the office laptop, use a checkout of the same selected revision, then run `helm lint <chart-path> --namespace <namespace> -f <selected-values-file>`, repeating `-f` in the same order for multiple files. Reproduce any edited YAML and the organization-specific overrides used by your working deployment. Preparation manages `imageName`, `imageTag`, `global.imageTag`, and `global.environment`; both image tags exactly match the selected version. If the normal command works but review fails, compare the effective values with the chart schema, as well as namespace, chart dependencies, Helm version and other required values such as cluster subdomain. Local output can contain values; remove secrets before sharing it.

Dependencies are resolved before validation. A failed locked build stops preparation; it does not silently fall back to updating versions. Repository declarations may use HTTPS, OCI, configured Helm aliases, or `file://` paths inside the CKP snapshot. Embedded URL credentials and paths outside CKP are rejected. Repository aliases are configured in `orchestrator.execution.helm-repositories`. Before resolving dependencies, the app runs `helm repo add <alias> <url> --force-update` once for each referenced alias. Each preparation uses its own temporary repository configuration/cache outside the CKP snapshot, removed with the preparation workspace. It does not use your personal Helm repository aliases. Other Helm state uses `<orchestrator.execution.workspace>/helm-config`, `helm-cache` and `helm-data`. For configured aliases, `helm-repository-connection` selects the existing Artifactory token session or Secret Server token reference to use for dependency downloads. The repository HTTPS host/port must match that connection. Direct HTTPS/OCI dependency URLs without aliases do not use this credential bridge. Do not put tokens into Chart.yaml. See [Helm dependency build](https://helm.sh/docs/helm/helm_dependency_build/) for lock-file behavior.

Helm lint can report both a template error and a missing-dependency warning. Diagnostics prioritize the error and mention the dependency warning separately. A missing helper at `templates/service.yaml:1` can mean that the service includes a shared/library chart template that was not checked into `charts/`. Check the `dependencies:` section of Chart.yaml, the referenced helper and the build steps normally used to populate `charts/`; a source-file location alone does not confirm that cause.


### Helm repository aliases

For a chart dependency such as:

```yaml
dependencies:
  - name: sng-common-helm-library
    version: 1.0.0
    repository: '@helm-release-virtual'
```

Set startup mappings in application.yaml (replace the example domain):

```yaml
orchestrator:
  execution:
    helm-repositories:
      helm-dev-virtual: https://artifactory.domain/artifactory/helm-dev-virtual
      helm-qa-virtual: https://artifactory.domain/artifactory/helm-qa-virtual
      helm-stable-virtual: https://artifactory.domain/artifactory/helm-stable-virtual
      helm-release-virtual: https://artifactory.domain/artifactory/helm-release-virtual
```

On CKP, override these under `application.orchestrator.execution.helm-repositories` in your values file; the chart renders them into the application ConfigMap. Names are flexible but must match Chart.yaml aliases exactly. `@name` and `alias:name` are supported. Only referenced aliases are registered, and these URLs point at Helm repositories, not Docker tag APIs. These are startup settings, so restart after changing them; they are not dashboard runtime overrides.

Authenticated alias registration uses `--username <short-name> --password-stdin --force-update`, passing the Artifactory token through stdin. Do not embed credentials in repository URLs. A registration failure stops preparation before deployment and identifies the alias without exposing raw command output. The download/build timeout uses `command-timeout-seconds` per command.


### Helm authentication and release lookup

Configure the existing Artifactory connection and token owner in application.yaml, or the corresponding `application.orchestrator.execution` block in CKP values:

```yaml
orchestrator:
  execution:
    helm-repository-connection: office
    helm-repository-username: first.last
```

The username must match the Artifactory token owner. Leaving it blank derives the short username from the current Secret Server AD login (`first.last@domain.net` or `DOMAIN\first.last` becomes `first.last`). A token-only Secret Server login has no verified username, so set it explicitly in that case. Secret Server still receives the full AD login. Its AD password is discarded after exchanging it for a Secret Server token; it is not reused for Helm. The `office` connection supplies an **Artifactory** token through its existing token session or credential reference, not the Secret Server token itself. Set `helm-repository-connection` to an empty string for anonymous repositories.

JFrog supports tokens for Helm repository authentication, but the organization must permit this token to read the selected Helm repository. The app passes the token via stdin, not argv or environment variables. Helm itself writes it to a private temporary repository configuration (directory mode 0700 on POSIX filesystems). That directory is deleted after dependency preparation, including ordinary failure, and is excluded from prepared snapshots. A process crash or filesystem cleanup failure can leave the temporary directory behind; remove abandoned preparation folders securely. No AD password is requested or saved for Helm.

**Helm release lookup** is a separate Kubernetes operation (`helm list`), unrelated to repository authentication. It now runs before source checkout/chart preparation and reports the service, namespace and context. The application reports recognized authentication, RBAC, TLS and network errors without returning raw output. If the login is expired, renew `oc login` for the kube-context used by the app. If access is forbidden, confirm the cluster identity can read Helm release records (normally Kubernetes Secrets) in that namespace. A lookup failure is not treated as an absent release.

To reproduce a lookup without making cluster changes, substitute your configured context:

```sh
helm --kube-context YOUR_CONTEXT list \
  --namespace ps-spoolers-sng-smtp-receiver --output json
kubectl --context YOUR_CONTEXT auth can-i list secrets \
  --namespace ps-spoolers-sng-smtp-receiver
```

Run these as the same OS user and with the same KUBECONFIG as the application. Preparation, profile-save and run-submission messages now appear in a sticky banner at the top of Configure run, with errors in the same visible area.

Helm 4 removed `helm list --all` and includes all release statuses by default. The application detects the installed Helm major version once per process: it uses `--all` on Helm 3 and omits it on Helm 4. This retains visibility of pending, failed and uninstalling releases. Restart the app if you replace the Helm binary while it is running. For the manual lookup command above, add `--all` when using Helm 3. See [Helm list](https://helm.sh/docs/helm/helm_list/).

### Service deployment or readiness failure

Version replacement and dependency resolution apply to both ordinary services and the load generator during Review run. `__REPLACEAPPVERSION__` is replaced in chart metadata, dependencies are built when a lock file exists (updated otherwise), and the resulting chart files and packages are saved in the prepared snapshot. Execution deploys that snapshot without downloading dependencies again.

A later deployment/readiness failure is separate from preparation. The error identifies service, namespace, release and context and classifies recognized failures (timeouts, hooks, ownership, authentication, permissions and Kubernetes validation). Unrecognized failures remain explicitly unclassified; raw output is not exposed because it can include rendered credentials.

Use those exact context, namespace and release values for read-only diagnosis:

```sh
helm --kube-context <context> status <release> --namespace <namespace>
helm --kube-context <context> history <release> --namespace <namespace>
kubectl --context <context> get pods,jobs --namespace <namespace>
kubectl --context <context> get events --namespace <namespace> --sort-by=.metadata.creationTimestamp
```

Inspect this output locally and remove credentials or sensitive values before sharing excerpts. Services may have been partially deployed before a timeout; retained service releases are not rolled back automatically. If service readiness fails, load generation has not started.

### Release changed since preparation

This preflight check stops the run before service deployment or load generation if a Helm release differs from the reviewed snapshot. Prepare a new run after another deployment, rollback or release-state change.

Helm 4 includes live Kubernetes objects in `helm status` under `info.resources`. The baseline excludes that transient field so pod readiness, resource versions and other live updates do not falsely invalidate the plan. Stored release fields, including revision, status, chart, values and manifest, remain checked. After upgrading from an application version that hashed live resources, prepare a fresh plan; old plans retain their original baseline hash. See the [Helm status implementation](https://github.com/helm/helm/blob/main/pkg/action/status.go).

Cleanup confirms the load release is absent; this can mean it was never installed. A preflight failure does not imply the service deployment itself failed.

## Command and API activity

Diagnostics are disabled by default. Enable them in `application.yaml` and restart:

```yaml
orchestrator:
  diagnostics:
    enabled: true
```

For CKP, set `application.orchestrator.diagnostics.enabled: true` in Helm values. When disabled, new diagnostic activity is not recorded and the diagnostic controls and API results are hidden. Existing database records are retained.

When enabled, **Show diagnostics** on Configure run, run details or monitoring reveals the console; the entire panel is otherwise hidden. Review run creates a diagnostic attempt. **Recent attempts** includes failures that never produced a plan. Real run details and monitoring show the preparation trace plus that run's submission, execution, cleanup and live-monitoring requests. Diagnostics persist across restart; historical runs have no retrospective traces.

Monitoring opens in a separate browser tab from the run toolbar. Its default view shows the time range and results; **Edit panels** reveals configuration. Log entries display their original text without an additional application timestamp.

The console records start/end timestamps, duration, command exit status and outbound HTTP status. Show it when needed; it refreshes every two seconds only while open with Live refresh enabled. Follow latest controls automatic scrolling, and Copy console copies the displayed text. It displays the latest 500 operations in chronological order. Hiding or pausing the console does not disable recording; use the YAML setting to disable recording. Recording is capped at 5,000 operations per attempt/submission. New command records retain executable/subcommand names, context, namespace, release/filter, paths, repository aliases, usernames and other operational arguments. Credential-bearing flags, assignments, URL userinfo and sensitive query parameters are masked with `[redacted]`. HTTP endpoint paths and nonsensitive query parameters remain visible. Bodies, headers, environment variables, stdin and raw process output remain excluded; unknown executables omit arguments. Earlier records keep their original, more conservative redaction. The application records its own HTTP calls and Git/Helm/kubectl invocations. Calls made inside those executables are represented by the command, not individual HTTP exchanges. Connection discovery/login before Review run is outside the run trace.

An unfinished `RUNNING` entry means no completion was recorded; after a crash it does not prove the command is still running. Logging does not change deployment behavior, and a database failure may leave a gap in diagnostics. The database retains diagnostic records; no automatic expiry is applied.

### Kubeconfig credential helpers

If Helm reports a kubeconfig credential-plugin error, Secret Server or Artifactory login will not fix that Kubernetes authentication step. The updated error can identify a missing helper executable or a helper exit code without exposing raw output. Inspect only the helper command and API version (not the entire raw kubeconfig, which can contain tokens):

```sh
kubectl --context sandbox-nvan config view --minify \
  -o 'jsonpath={.users[0].user.exec.command}{"\n"}{.users[0].user.exec.apiVersion}{"\n"}'
helm --kube-context sandbox-nvan list \
  --namespace sng-smtp-receiver --output json
```

Use the actual configured namespace. Run from the same terminal as the application and compare PATH/KUBECONFIG. If the helper is missing, install the organization-approved helper or configure its absolute executable path through your approved kubeconfig setup. If the helper exits unsuccessfully, inspect its local diagnostic and renew its organization-specific login. If its ExecCredential API version or interactiveMode is rejected, update the helper/kubeconfig according to the CKP team's supported client configuration. Do not change API-version strings blindly or disable TLS validation.

If the helper is specifically `kubectl` and exits with code 1, the executable was found: investigate its exec arguments/subcommand and local error, rather than assuming kubectl is missing. The app inherits its parent environment but runs external commands without a terminal and closes stdin (except when passing a Helm repository token). Complete any required interactive organization login in the launching terminal first; a helper that still requires interactive input needs the CKP team’s supported noninteractive/cached-login configuration.


## Add traffic to an active run

In real mode, open the run and use **Load installations → Add load** while the baseline is running. Select a configured load-generator service, Git reference, image version and values files. Edit its YAML for the new traffic pattern, choose **Review additional load**, check the generated release name and effective values, then choose **Install additional load**.

Each addition gets a unique `load-<UUID>` Helm release in the service's configured namespace. The same load-generator service can be added multiple times with different values. Preparation checks out CKP, replaces chart version placeholders, resolves dependencies and validates the chart using that new release name. Existing baseline load and services are not upgraded or uninstalled by adding traffic. Repeated installation submissions do not create duplicate releases.

The chart must support simultaneous releases: Kubernetes resource names and selectors must be release-specific. If its values force fixed names (`fullnameOverride`, deployment suffixes, or custom name fields), adjust those in the YAML for the added load. The orchestrator cannot infer arbitrary chart naming conventions and will not take ownership of conflicting resources.

The run page records each addition's image, namespace, release, actor and installation/cleanup status; command activity is attached to the same run. An installation error attempts cleanup of only that additional release, leaving baseline traffic running. If that cleanup fails, the addition is marked CLEANUP_FAILED and cleanup is retried when the parent ends. “RUNNING” means Helm installation completed; use monitoring to verify actual traffic.

Additional loads share the parent's remaining measurement window and overall deadline; they do not extend either. Choose a long enough window when configuring the baseline. Completion, cancellation and restart recovery clean up **all** load releases owned by the run, retaining service deployments. Cleanup failures retain the environment reservation and require recovery. This addition workflow is available in real mode; simulation retains its original single-load workflow.

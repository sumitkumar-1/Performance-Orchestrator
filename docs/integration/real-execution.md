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

1. Create or load a saved profile.
2. Click **Add service** to configure a deployment in a dialog. Choose the load generator in its own dialog; it cannot also be a service deployment.
3. Git branches/tags and all image-version pages load automatically. The selector contains branches and tags only. On editing a profile it restores the named reference when available; otherwise it prefers `master`, then the configured revision or an available reference. There is no Saved commit option. Exact commits remain pinned internally for the prepared deployment. The image dropdown includes release tags and development hashes, with a filter for long lists.
4. CKP `values*.yaml` / `values*.yml` files load automatically from the selected Git revision. The environment values file is selected when present. Select one or more files; the editor immediately displays the selected YAML. For multiple files, the numbered editor selector shows merge order; use **Apply earlier** to reorder. Later files take precedence.
5. Edit YAML if needed. Edits replace that file's contents for preparation and are saved with the run profile; repository files are untouched. **Restore file** discards edits to the displayed file. Helm still applies chart defaults; use YAML null where Helm requires removal of a default key. Older profiles retain their additional overlay under **Existing profile overlay**.
6. Set measurement duration: the observation window after warmup. **Advanced timing** contains warmup and the overall timeout, which includes deployment. On timeout, cleanup starts; in-flight commands and cleanup can take additional time. The load tool's YAML duration should cover warmup plus measurement.
7. Add monitoring panels if needed. In a metric panel, optionally enable **Pass/fail check** and enter its maximum acceptable value; no separate threshold JSON section is needed. Then click **Review run**. Review the prepared values and target, confirm, and choose **Start run**.

Service namespace/release settings come from Services, including environment-specific destination exceptions. Preparation does not install charts. It fetches and snapshots CKP, resolves image digests, renders/lints charts and reads Helm release baselines. Plans expire after 15 minutes. Configuration changes, changed image tags or baseline drift require a new plan.

The initial Helm contract follows the supplied organization example: `imageName`, `imageTag`, `global.imageTag` and `global.environment` are managed. Image tags are passed as `version@sha256:...`, pinning the resulting container reference. The rendered chart must contain a container image pinned to that digest. Charts that ignore these keys or forbid digest-qualified tags need an explicit adapter adjustment before use. All other values—including rate, destinations, cluster subdomain and the load tool's own duration—come from the selected YAML/overlay. The orchestrator does not guess those field names. No values are silently rewritten to simulated virtual-user or request-rate fields.

Use Kubernetes Secret references in values. Prepared chart snapshots and values are stored in the application database for repeatability; do not paste credentials into a profile. Git/Artifactory tokens are not persisted with the plan.

## 4. Execution and stop behavior

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

On the office laptop, use a checkout of the same selected revision, then run `helm lint <chart-path> --namespace <namespace> -f <selected-values-file>`, repeating `-f` in the same order for multiple files. Reproduce any edited YAML and the organization-specific overrides used by your working deployment. Preparation manages `imageName`, `imageTag`, `global.imageTag`, and `global.environment`; both image tags use `version@sha256:digest`. If the normal command works but review fails, compare those digest-qualified values with the chart schema, as well as namespace, chart dependencies, Helm version and other required values such as cluster subdomain. Local output can contain values; remove secrets before sharing it.

Dependencies are resolved before validation. A failed locked build stops preparation; it does not silently fall back to updating versions. Repository declarations may use HTTPS, OCI, configured Helm aliases, or `file://` paths inside the CKP snapshot. Embedded URL credentials and paths outside CKP are rejected. Repository aliases are configured in `orchestrator.execution.helm-repositories`. Before resolving dependencies, the app runs `helm repo add <alias> <url>` once for each referenced alias. Each preparation uses its own temporary repository configuration/cache outside the CKP snapshot, removed with the preparation workspace. It does not use your personal Helm repository aliases. Other Helm state uses `<orchestrator.execution.workspace>/helm-config`, `helm-cache` and `helm-data`. Portal Artifactory/Secret Server token sessions are not yet forwarded to dependency downloads. Authenticated repository access needs its repository URL/authentication mapping confirmed before it can be wired to those sessions. Do not put tokens into Chart.yaml. See [Helm dependency build](https://helm.sh/docs/helm/helm_dependency_build/) for lock-file behavior.

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

Registration follows the supplied command without username/password flags. If the endpoint requires authentication, portal token forwarding needs a separate integration; do not embed credentials in repository URLs. A registration failure stops preparation before deployment and identifies the alias without exposing raw command output. The download/build timeout uses `command-timeout-seconds` per command.

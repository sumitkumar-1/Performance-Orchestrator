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

Source preparation uses Git fetch with `--depth=1 --filter=blob:none` and non-cone sparse checkout restricted to `/ckp/`. The server can ignore partial-clone filtering, so this guarantees a CKP-only working tree, not a guarantee of minimal network transfer. The selected revision resolves to a commit and only that snapshot is used. Symlinks, paths outside CKP, snapshots above 16 MiB/2000 files and missing charts are rejected. Dependencies must be vendored under the repository's CKP chart; no automatic dependency downloads/submodules occur.

Use token sessions or Secret Server token references for Git/Artifactory. Sign in before fetching files/preparing. Git authorization is supplied through child-process environment settings, never command arguments, URLs, Git config files or logs. Chart output is withheld on failure because manifests can contain secrets; errors identify the failed operation and exit code. Inspect an approved local checkout with Helm directly if chart lint/render errors need deeper investigation.

## 3. Prepare a profile

Open Overview → Configure run.

1. Create or load a saved profile.
2. Click **Add service** to configure a deployment in a dialog. Choose the load generator in its own dialog; it cannot also be a service deployment.
3. Git branches/tags and all image-version pages load automatically. Git defaults to `master` when available, otherwise the configured revision or an available reference. The image dropdown includes release tags and development hashes, with a filter for long lists.
4. CKP `values*.yaml` / `values*.yml` files load automatically from the selected Git revision. The environment values file is selected when present. Select one or more files; the editor immediately displays the selected YAML. For multiple files, the numbered editor selector shows merge order; use **Apply earlier** to reorder. Later files take precedence.
5. Edit YAML if needed. Edits replace that file's contents for preparation and are saved with the run profile; repository files are untouched. **Restore file** discards edits to the displayed file. Helm still applies chart defaults; use YAML null where Helm requires removal of a default key. Older profiles retain their additional overlay under **Existing profile overlay**.
6. Set measurement duration: the observation window after warmup. **Advanced timing** contains warmup and the overall timeout, which includes deployment. On timeout, cleanup starts; in-flight commands and cleanup can take additional time. The load tool's YAML duration should cover warmup plus measurement.
7. Optionally supply LogQL measurements and thresholds, then click **Review run**. Review the prepared values and target, confirm, and choose **Start run**.

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

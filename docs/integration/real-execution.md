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

Open Overview → Configure a real run.

1. Create or load a saved profile.
2. Add service deployments. The load generator is selected separately and must not also be listed as a normal service deployment.
3. Fetch image versions and Git tags/branches. Select the image version and Git revision.
4. Load CKP values files. This pins the displayed files to the returned commit. Select one or more files; their selected order controls merging. To edit a profile, copy one selected file into the YAML editor and change it, or supply a smaller overlay.
5. Set warmup, measurement time and the maximum run duration. Allow enough time for service installation/readiness before load starts.
6. Optionally supply LogQL measurements and thresholds.
7. Prepare, review the namespaces, releases, commit, image digest and values changes, then explicitly confirm Start real run.

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

Measurements are optional JSON rows in the real-run form. Each query uses the selected service's namespace and environment-specific monitoring credential, against its environment's shared Loki connection:

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

The initial adapter collects at the end of the measurement window using its end timestamp. It is not a live chart or raw log viewer. Ingestion lag and query window semantics must be accounted for in the approved queries. Monitoring credentials are resolved at submission and retained in memory only for the active run, then discarded; expired upstream credentials produce unavailable evidence. No background Secret Server password login or token renewal occurs.

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

# Performance Environment Orchestrator

A Java application for preparing service deployments, configuring performance tests, and reviewing run history and reports. The browser UI provides deployment selection, plan previews, run controls, connection management, and runtime configuration editing.

**Current operation:** simulation is available by default. Real mode supports connection diagnostics and opt-in Helm deployment, timed load execution, owned-load cleanup and configured LogQL measurements. Organization chart compatibility and sandbox verification are required; shared-user authentication is not implemented.

## Requirements

- JDK 21 or newer; the compiler targets Java 21.
- Maven 3.6.3+ for builds, with access to Maven Central or an approved mirror.
- Docker or a compatible container builder for the CKP image.
- Helm 3, kubectl, an approved image repository and an existing namespace for a CKP pilot.

The backend uses Spring Boot 3.5.16, H2 and Flyway. The frontend uses HTML, CSS and JavaScript modules.

## Build and run locally

Set `JAVA_HOME` to your JDK if needed. The script adds its `bin` directory to `PATH`.

```sh
./scripts/run-local.sh build       # Compile, run tests and package
./scripts/run-local.sh run         # Run the existing package
./scripts/run-local.sh build-run   # Build, test and run
```

Open [the local application](http://127.0.0.1:8080). With the default mock catalog, the application creates a sample profile when no profiles exist. Its results are synthetic and no load is sent to external services.

Pass application arguments after `--`:

```sh
./scripts/run-local.sh run -- --server.port=8081
./scripts/run-local.sh run -- --orchestrator.connections=.local/connections.yaml
```

The application currently binds to loopback only. Stop it with Ctrl-C.

## Simulation and real mode

Choose the mode at startup:

```sh
./scripts/run-local.sh build-run --mode simulation
./scripts/run-local.sh run --mode real
# Equivalent direct application option:
java -jar target/perf-orchestrator-0.1.0.jar --orchestrator.mode=real
```

| Capability | Simulation (default) | Real |
| --- | --- | --- |
| Demo services, builds and starter profile | Loaded from mocks | Not loaded |
| Deployment and performance test workflow | Simulated | Opt-in real Helm execution; organization chart/query configuration required |
| Delinea sign-in and secret retrieval | Available for testing connections | Live configured connections |
| Artifactory discovery and digest resolution | Available separately in Settings | Live configured connections |
| Synthetic measurements | Yes, labeled simulated | Never generated |

Real mode supports read-only diagnostics and opt-in Helm execution through its own preparation/run APIs. Configure `orchestrator.execution` before running. It uses sparse CKP checkouts, pinned commits and exact selected image tags (registry digests checked at submission), service Helm readiness, a separately owned load release, optional LogQL results and explicit cleanup. The simulation planning API remains unavailable in real mode. Follow the [real execution pilot guide](docs/integration/real-execution.md) for prerequisites, cluster binding, chart contracts and limitations.

Simulation keeps its existing state in `data/`. Real mode defaults to its own database, runtime settings and artifacts under `data/real/` and loads the example real catalog and connections from application.yaml. Replace example settings in that file, Helm values, or Connections & catalog before contacting real services. Avoid overriding the datasource/configuration paths to share them between modes. Stored catalogs must match the startup mode. A restart is required to change mode; the runtime editor cannot switch it underneath active requests or runs.

For CKP, set `mode: simulation` or `mode: real` in your Helm values. Changing it triggers a rollout. Both modes remain accessible through port-forwarding only.

## Configuration

- `src/main/resources/application.yaml`: common/CKP settings such as port, database and resource paths.
- `src/main/resources/config/schemas/`: validation schemas (not editable environment settings).
- `docs/integration/examples/`: optional bootstrap examples, not packaged runtime configuration.
- `src/main/resources/mocks/`: demonstration catalog, chart/values inputs and starter profile.

Use **Connections & catalog** to edit environment monitoring, service destinations, Secret Servers, credential references, Artifactory/Bitbucket connections and service image/source mappings using forms. Connection and service entries can be added or deleted; referenced entries cannot be deleted until their references are updated. The real instance environment/cluster is fixed at startup. Simulator project additions and simulation-only fields remain in Advanced JSON. Scenario templates are read-only in the normal settings view; real execution profiles are saved from Configure a run. Saves are validated and applied without restart. Conflicting edits are rejected; active runs retain their prepared inputs. Connection changes invalidate portal tokens and require a fresh sign-in.

Field-level overrides in `data/configuration.json` (or `data/real/configuration.json` in real mode) are applied over packaged or externally supplied defaults on restart. Untouched fields receive new defaults. Changes to resource files alone are not watched. Port, database, worker scheduling and application mode remain startup settings.

Secret Server supports AD sign-in or supplied Bearer tokens with `authMode: interactive` and a configured OAuth token URL. AD passwords are exchanged and discarded; the returned token expiry and username are tracked. Artifactory and Bitbucket use supplied or vault-referenced Bearer tokens. Never put passwords or tokens in configuration. Artifactory/Bitbucket can alternatively resolve a token credential reference. Secret Server also supports mounted/environment tokens or a token from another vault. See [connection configuration](docs/adapters/connections.md).

## Helm environment overrides

One `application.yaml` supplies defaults for both local and CKP runs. Select simulation or real mode with `orchestrator.mode`; override CKP settings through Helm values. No separate hosting profile is required.

The pilot uses embedded H2 in the application process, backed by the PVC on CKP. Both local and CKP retain the existing empty database password. There is no required database Kubernetes Secret or vault fetch at startup. Persistence and existing database paths remain unchanged. If you previously changed an existing database's password, supply that password through an approved runtime override; this change does not reset database credentials.

Organization secret provisioning is deferred until the `secretDockerImage`/`pistol` contract is available. An init container can write files on a shared volume; it cannot directly set another container's environment. A future integration can read those files through Spring configuration or an application entrypoint. Existing Delinea, environment Secret and token-file support for external integrations remains available.

`values.yaml` and `application.yaml` include the supplied sanitized Delinea and Artifactory URL patterns. Secret IDs, service image paths and Git revisions are examples only: replace them and verify field slugs. These defaults do not make outbound requests at startup.

Override `connections` in the chosen `values-env.yaml`. Helm mounts it as a connections file; it overrides the application's `orchestrator.connection-defaults`. For example:

```yaml
connections:
  secretServers:
    office-vault:
      apiBaseUrl: https://YOUR_VAULT/SecretServer/api/v1
      tokenUrl: https://YOUR_VAULT/SecretServer/oauth2/token
  artifactory:
    office:
      apiBaseUrl: https://YOUR_REGISTRY/artifactory/api/docker
  credentials:
    registry-reader:
      secretId: 'YOUR_NUMERIC_SECRET_ID'
  imageSources:
    dev:
      displayName: Dev images
      connectionRef: office
      repositoryKey: YOUR_DOCKER_REPOSITORY
      imagePaths:
        smtp-receiver: YOUR_IMAGE_PATH
      usernameRequired: false
      pullRepositoryTemplate: YOUR_REGISTRY/{repositoryKey}/{image}
application:
  orchestrator:
    tick-ms: 2000
```

Helm merges these entries over base values. For a global-token authentication mode, remove the inherited portal token URL explicitly with `tokenUrl: null`, set `authMode: file` and `bearerTokenFile: /var/run/secrets/delinea/token`, and set the top-level `tokenSecret` to an existing Kubernetes Secret name. Alternatively, use `authMode: environment`, `bearerTokenEnvironmentVariable` and top-level `environmentSecret`. Never put passwords or tokens in values or `application`.

The chart mounts `application` as additional Spring configuration. Chart-owned command-line arguments for mode, loopback address, port and connection/configuration file paths take precedence. ConfigMap changes trigger a rollout. **Saved portal changes override only the fields changed from startup settings**; update those through the portal if a persisted configuration exists. Other startup changes require restart/rollout. Local runs can override `orchestrator.connection-defaults` using normal Spring configuration or continue supplying `--orchestrator.connections=PATH`.

Grafana/Loki URLs and per-namespace monitoring credentials remain documented integration contracts, not executable monitoring settings yet.

## Configuration defaults, live edits and import/export

All editable sections have a startup configuration location:

| Section | Application YAML | Helm values |
| --- | --- | --- |
| Artifactory, Bitbucket, Secret Server, credentials, legacy image sources | `orchestrator.connection-defaults` | `connections` |
| Environments, services, mock image sources, scenarios | `orchestrator.catalog-defaults` | `catalog` |

Real defaults cover sandbox, dev, qa, stable, perf and perf3. Each instance exposes only its startup-selected environment. Service namespace/release/values defaults are shared, with optional per-environment exceptions. Monitoring URLs and secret IDs are illustrative and must be replaced with organization-approved values. Real mode no longer requires simulation limits or allowedActions. Simulation still loads its packaged mock catalog. An explicit `orchestrator.catalog` or `orchestrator.connections` file takes precedence over the corresponding inline defaults. Real service entries validate metadata, namespace references and relative paths without requiring a local checkout. Simulation still validates project files and installation bindings. Remote Git retrieval and real execution require the opt-in execution settings described below.

For local overrides without rebuilding, create an ignored `.local/application.yaml` with only the properties you want to change:

```yaml
orchestrator:
  connection-defaults:
    secretServers:
      office-vault:
        apiBaseUrl: https://YOUR_VAULT/SecretServer/api/v1
        tokenUrl: https://YOUR_VAULT/SecretServer/oauth2/token
  catalog-defaults:
    environments:
      dev:
        displayName: Dev
        clusterIdentity: YOUR_DEV_CLUSTER
        serviceNamespaces: [YOUR_SERVICE_NAMESPACE]
        loadGeneratorNamespace: YOUR_LOAD_NAMESPACE
        allowedActions: [PLAN]
        limits:
          maxRunDurationSeconds: 300
          maxVirtualUsers: 1
          maxRequestsPerSecond: 10
```

```sh
./scripts/run-local.sh run --mode real -- --spring.config.additional-location=file:.local/application.yaml
```

Spring merges these startup properties over packaged defaults. On CKP, put the same environment map under `catalog.environments` in your environment values file. Helm merges base/environment values and renders the application ConfigMap. `application` in Helm values can additionally override Spring startup properties, including catalog defaults. Restart/rollout is required for startup-file changes.

In **Connections & catalog → Advanced JSON · import, export & restore**:

- The source label identifies startup settings versus saved runtime overrides. The editor initially shows the complete document; section views are also available.
- **Save configuration** validates and applies all maps together immediately, then persists the complete configuration. Connection changes require a fresh vault sign-in.
- **Export active configuration** downloads JSON containing the active catalog and connection references. It excludes unsaved editor drafts and does not resolve passwords or tokens. Arbitrary values entered in the catalog are included: do not put plaintext secrets there, and review exports before sharing.
- **Import JSON…** loads an exported file into the Complete configuration editor, displaying the entire JSON document. Review the document or switch sections and click Save to validate/apply. Import previews a complete configuration, then saves its differences from startup defaults; it must match simulation/real mode and the instance environment/cluster and is limited to 256 KiB. The destination's current edit revision is used; stale saves remain rejected.
- **Load startup defaults into editor** loads the startup snapshot for review. Saving it clears field overrides; an empty override file remains and future defaults apply.

Saved runtime JSON stores a versioned list of changed fields, including explicit removals. Arrays/lists are overridden as a whole. On restart these changes are applied to the latest startup defaults; unrelated default changes are retained. Configuration is validated before use, so incompatible defaults/overrides fail clearly instead of silently dropping user changes. A fresh PVC starts from defaults; a reinstall reusing a retained PVC retains overrides.

Legacy full-document runtime JSON and full configuration exports remain readable. The next save converts them to field overrides relative to the current startup defaults. Since older files do not record the original baseline, all differences must be preserved; review and restore startup defaults if you want to discard stale legacy values.

To import an export at startup, copy it to an ignored writable path and select it as the runtime configuration file:

```sh
./scripts/run-local.sh run --mode real -- --orchestrator.configuration-file=.local/imported-runtime.json
```

The file must already contain the complete exported JSON; if absent, startup defaults are used. Subsequent portal saves convert/update this selected file as a versioned override document. Use portal Export for a portable complete configuration, not the internal override file. For CKP, an imported runtime JSON must be placed at the configured writable PVC path before startup; the chart does not provision exports onto the PVC. Keep exports separate from Spring YAML and Helm values—they use different document wrappers. Invalid imports are rejected rather than silently falling back to defaults.

## Runtime data

`data/` is generated local state, not source code:

| Path | Contents |
| --- | --- |
| `data/orchestrator.mv.db` | Profiles, plans, run history, events, simulated operations and reservations |
| `data/configuration.json` | Runtime configuration saved through Settings; created on first save |
| `data/artifacts/` | Generated report downloads |

Keep this directory to retain local work. It is ignored by Git and excluded from the container build context. Back it up while the app is stopped. Removing it resets local history, profiles and saved settings; it is recreated on startup. See [local operations](docs/operations/local.md).

## CKP packaging and deployment

The chart is at `ckp/helm/ps-spoolers-perf-orchestrator/`, containing `Chart.yaml`, `values.yaml`, `templates/` and `charts/` for future dependencies. The container definition is `ckp/Dockerfile`.

This chart deploys the **orchestrator application**, not the services selected in a performance run. The current pilot runs one replica, retains H2/settings on a PVC by default, and is accessible through pod port-forwarding. Service and Ingress templates are reserved for shared hosting and explicitly reject enabling access until the application supports shared authentication. Helm renders files from [`templates/`](https://docs.helm.sh/docs/chart_template_guide/getting_started/).

Build and publish to your approved registry, replacing the example image below:

```sh
./scripts/run-local.sh build
docker build -f ckp/Dockerfile -t registry.example.net/team/ps-spoolers-perf-orchestrator:0.1.0 .
docker push registry.example.net/team/ps-spoolers-perf-orchestrator:0.1.0
```

The chart includes `values-sandbox.yaml`, `values-dev.yaml`, `values-qa.yaml`, `values-stable.yaml`, `values-perf.yaml` and `values-perf3.yaml`. These select real mode without inventing organization URLs or credentials. Layer one over base `values.yaml`, then apply local organization settings. The installation environment is independent of the target performance-test environment.

Create an ignored `.local/ckp-values.yaml` with your image and storage settings:

```yaml
image:
  repository: registry.example.net/team/ps-spoolers-perf-orchestrator
  tag: '0.1.0'
persistence:
  enabled: true
  storageClass: '' # Set the CKP-approved class, or leave empty for the default
```

Render locally before deploying:

```sh
./scripts/deploy-ckp.sh render --namespace YOUR_NAMESPACE --values ckp/helm/ps-spoolers-perf-orchestrator/values-dev.yaml --values .local/ckp-values.yaml
./scripts/deploy-ckp.sh deploy --context YOUR_CKP_CONTEXT --namespace YOUR_NAMESPACE --values ckp/helm/ps-spoolers-perf-orchestrator/values-dev.yaml --values .local/ckp-values.yaml
```

The script requires an explicit context for deployment, uses the existing namespace, and runs `helm upgrade --install --wait`. It does not create namespaces, build/push images, switch your current context or retrieve credentials. `--release` and repeatable `--values` options are supported; see `--help`.

Access the pilot with a free local port:

```sh
kubectl --context YOUR_CKP_CONTEXT --namespace YOUR_NAMESPACE port-forward deployment/ps-spoolers-perf-orchestrator 8081:8080
```

Open [the forwarded application](http://127.0.0.1:8081). The chart's probes require a runtime image with Bash (included by the default Ubuntu-based Temurin image). Existing Kubernetes Secret references can supply environment credentials or a mounted Delinea token; secret provisioning and renewal remain external. Bootstrap connection ConfigMap changes trigger a rollout, but saved runtime overrides still take precedence.

The PVC is retained on chart removal. Backups, namespace permissions, storage class, CA trust and image-pull credentials must match your CKP environment. No live CKP deployment has been validated.

## API and tests

The [OpenAPI document](src/main/resources/static/openapi.json) is served at `/openapi.json`; endpoints use `/api/v1`. Mutating requests require the session cookie and CSRF header obtained from `/api/v1/session`.

`./scripts/run-local.sh build` runs the test suite. Tests cover configuration persistence/validation, secret authentication, registry contracts, planning, orchestration, concurrency and restart recovery. Remote systems are mocked in tests.

For an office laptop without Codex, start with the [step-by-step real integration runbook](docs/integration/office-laptop-runbook.md), [minimal pilot configuration](docs/integration/examples/office-pilot-connections.yaml), and [sanitized support report template](docs/integration/support-report-template.md). Use that runbook for connection setup, then follow the real execution guide below for the Helm pilot.

For pending organizational contracts, use the [integration questionnaire](docs/integration/organization-questionnaire.md) and [monitoring contract](docs/integration/monitoring-contract.md). Local implementation notes are in ignored `TASK.md`.

Outstanding organization inputs are tracked in [open integration questions](docs/integration/open-questions.md), including load YAML keys, lifecycle signals and metrics queries.

### Token sign-in prompt

The real-mode UI prompts for a Secret Server REST API Bearer access token and its remaining lifetime (up to eight hours). Artifactory and Bitbucket have separate token sessions under Connections & catalog. Tokens stay server-side, are not exported, and must be supplied again after expiry, sign-out, configuration changes or restart. Session duration does not extend provider validity; an upstream 401 requires a new token. Signing out clears local access but does not revoke a portal-generated token at its provider.

This is integration authentication, not shared-user authorization for publicly hosting the application. Use Secret Server `authMode: interactive` for AD or token sign-in; legacy `portal` entries still migrate to token-only mode.


## One instance per environment

Deploy with `values-sandbox.yaml`, `values-dev.yaml`, `values-qa.yaml`, `values-stable.yaml`, `values-perf.yaml` or `values-perf3.yaml`. Their `targetEnvironment` setting is passed as `--orchestrator.target-environment` and cannot be changed by dashboard edits/imports. Locally, real mode defaults to sandbox; pass `--orchestrator.target-environment=perf3` to test another startup binding. Only the chosen environment appears in the effective catalog and export. Imports must match its environment ID and cluster identity.

All environment defaults remain in source control. `deploymentDefaults` holds each service's shared namespace, release and values files. `deploymentByEnvironment` is only for exceptions. Load-profile YAML remains authoritative; the sample scenario is metadata, while real run profiles select repository YAML and optional overrides. Environment monitoring references a shared `connections.loki` entry. Each service owns its namespace and `monitoringCredentials` map (environment → credential reference), used for both logs and LogQL metrics. Real runs can collect configured LogQL measurements at the end of the measurement window. Repository checkout and Helm execution are opt-in; service-account RBAC must be supplied by your CKP administrators.

Example secret IDs/URLs are placeholders, not live organization credentials. Dashboard changes remain instance-local. Existing snapshots created for another environment/cluster must be reviewed and adapted before importing; the application refuses to switch its target to accommodate an import.

### Connection settings in the portal

- **Secret Servers**: `office-vault` is the example Delinea connection. Configure its API base, then use **Sign in** to provide a Bearer access token.
- **Credential references**: map a secret ID and username/password field slugs to that vault. Services select one reference per environment for both logs and LogQL metrics.
- **Artifactory connections**: `office` is the example registry server and its credential reference.
- **Services → Container image**: select `office`, repository stage `dev` or `stable`, team ID and image name. The tags request is `{apiBaseUrl}/docker-{repoStage}/v2/{teamId}/{imageName}/tags/list`. Existing `office-dev` image-source mappings remain supported as legacy configuration.
- **Bitbucket connections**: `office-stash` is the example Stash REST API connection. Services specify project key, repository slug, Git revision and Helm chart path. In **Connection diagnostics**, select the service and choose **Git branches** or **Git tags**. Real preparation discovers the HTTPS clone URL through Bitbucket and checks out CKP files at the selected revision.
- **Connection diagnostics** has one service selector and operation selector for container image versions, Git branches and Git tags. Image discovery also supports pagination and digest resolution. Overview links directly to this panel. Sign in from the Secret Servers section first when required.

Real-mode environment exports omit simulator limits, allowed actions, dashboard URLs and legacy namespace lists. Service deployment destinations own namespaces; their monitoring credential references are keyed by environment. Old real-mode imports containing those simulator fields remain readable; the unused fields are discarded. Simulation keeps its existing controls.

### Authentication choices and credential lifetime

Artifactory and Bitbucket accept `authMode: token` or `secret-server`. Defaults use interactive token sessions. Token references must contain `tokenFieldSlug` or `tokenEnvironmentVariable`; username/password references are rejected when used by these integrations. Loki namespace credential references remain independent.

Artifactory/Bitbucket session tokens are encrypted in server memory and cleared on sign-out, expiry, session invalidation or configuration changes. Browser close alone does not immediately invalidate a server session; idle timeout is 30 minutes. No automatic renewal or upstream revocation is performed. Use a Bearer-compatible access token, not a legacy API key requiring another header.

### Shared Loki connections and scenario display

Settings now separates **Loki connections** from **Environment** and **Services**. Configure a URL once, select it from each environment, and assign monitoring credentials under the service. The namespace comes from that service's deployment destination. Defaults group sandbox/dev/qa under `lower`, and perf/perf3/stable under `higher`; update these example groups/URLs as needed. Both logs and log-derived metrics use LogQL. See the [monitoring configuration and query examples](docs/integration/monitoring-contract.md).

Scenario templates are read-only cards with labeled properties and lists. Advanced JSON import/export is still available for full configuration backups and bulk edits.

### Test configuration

Spring tests explicitly load `src/test/resources/application-test.yaml` instead of runtime `application.yaml`. It contains fixed example fixtures, in-memory databases and temporary paths under `target/`; it is not packaged in the application JAR. Change office URLs, credentials references and authentication modes in the runtime YAML or Helm values without editing this test fixture. Run `mvn verify` (also run by `scripts/run-local.sh build-run`). Authentication integration tests cover supplied tokens and Secret Server token references independently of deployment defaults.

## Real performance execution

Use [the real execution guide](docs/integration/real-execution.md) to configure an explicit kube-context/API server, service repositories and load values. Overview provides profile preparation and a reviewed start action when execution is enabled. Each checkout contains only CKP working-tree files. No office deployment was performed during development; sandbox verification is still required.

Runs now offer **Open monitoring** for configurable LogQL log/metric panels with per-service credential defaults, namespace overrides and live range queries. See [authentication and live monitoring](docs/integration/real-execution.md#live-monitoring).

Monitoring panels can be reused independently of run profiles: choose **Monitoring → Save as new set**, then select a saved set and **Load set** on a future run. Sets persist in the application database for the bound environment. See [reusing monitoring panels](docs/integration/real-execution.md#reusing-monitoring-panels).

Optional real-run diagnostics are disabled by default. Set `orchestrator.diagnostics.enabled: true` in `application.yaml` (CKP: `application.orchestrator.diagnostics.enabled` in Helm values) and restart to enable recording and **Show diagnostics** buttons. The console stays hidden until opened. It records safe command/API summaries from preparation through execution, with timings and exit/HTTP statuses. Failed preparation attempts remain inspectable from Configure run. See [diagnostic activity and credential-helper troubleshooting](docs/integration/real-execution.md#command-and-api-activity).

Run service order is configured with drag handles or up/down buttons in Configure run, including when loading saved profiles. Both simulation and real execution follow the displayed order. Legacy service dependency lists are ignored.


While a real run is generating load, use **Load installations → Add load** on its run page to review and install another traffic pattern as an independent Helm release. Existing load and service deployments remain untouched. Additional loads share the parent run's time limits and are cleaned up when it ends. See [additional-load operation and chart requirements](docs/integration/real-execution.md#add-traffic-to-an-active-run).

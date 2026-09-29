# Performance Environment Orchestrator

A Java application for preparing service deployments, configuring performance tests, and reviewing run history and reports. The browser UI provides deployment selection, plan previews, run controls, connection management, and runtime configuration editing.

**Current operation:** deployment and load execution are simulated. Configured Artifactory discovery and Delinea secret retrieval make real read-only requests. Live CKP service deployment, load-generator control, metrics querying and shared-user authentication are not implemented yet.

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
| Deployment and performance test workflow | Simulated | Unavailable until real adapters are integrated |
| Delinea sign-in and secret retrieval | Available for testing connections | Live configured connections |
| Artifactory discovery and digest resolution | Available separately in Settings | Live configured connections |
| Synthetic measurements | Yes, labeled simulated | Never generated |

Real mode is currently a **read-only integration workspace**, not a completed real deployment engine. It contains no simulation adapter beans, does not run the mock worker, and rejects planning/run submission with `REAL_EXECUTION_UNAVAILABLE`. Open Connections & catalog to configure real sources, sign in and browse images. Mock workflow screens are hidden. Neither mode enables shared network access.

Simulation keeps its existing state in `data/`. Real mode defaults to its own database, runtime settings and artifacts under `data/real/` and starts with an empty real catalog. Configure real connection mappings there, or supply an initial connections file. Avoid overriding the datasource/configuration paths to share them between modes. Stored catalogs must match the startup mode. A restart is required to change mode; the runtime editor cannot switch it underneath active requests or runs.

For CKP, set `mode: simulation` or `mode: real` in your Helm values. Changing it triggers a rollout. Both modes remain accessible through port-forwarding only.

## Configuration

- `src/main/resources/application.yaml`: common/CKP settings such as port, database and resource paths.
- `src/main/resources/config/schemas/`: validation schemas (not editable environment settings).
- `docs/integration/examples/`: optional bootstrap examples, not packaged runtime configuration.
- `src/main/resources/mocks/`: demonstration catalog, chart/values inputs and starter profile.

Use **Connections & catalog → Edit runtime configuration** to update environments, limits, services, namespace/release mappings, image sources, scenarios, connections and credential references. Saves are validated and applied without restart. Conflicting edits are rejected; active runs retain their prepared inputs. Connection changes invalidate portal tokens and require a fresh sign-in.

Saved overrides in `data/configuration.json` (or `data/real/configuration.json` in real mode) take precedence over packaged or externally supplied defaults on restart. Changes to resource files alone are not watched. Port, database, worker scheduling and application mode remain startup settings.

Delinea authentication supports portal sign-in, an environment token or a mounted token file. Secrets are fetched using configured secret IDs and field slugs; passwords and tokens do not belong in configuration files. See [connection configuration](docs/adapters/connections.md) and [connection examples](docs/integration/examples/connections.yaml).

## Helm environment overrides

One `application.yaml` supplies defaults for both local and CKP runs. Select simulation or real mode with `orchestrator.mode`; override CKP settings through Helm values. No separate hosting profile is required.

The pilot uses embedded H2 in the application process, backed by the PVC on CKP. Both local and CKP retain the existing empty database password. There is no required database Kubernetes Secret or vault fetch at startup. Persistence and existing database paths remain unchanged. If you previously changed an existing database's password, supply that password through an approved runtime override; this change does not reset database credentials.

Organization secret provisioning is deferred until the `secretDockerImage`/`pistol` contract is available. An init container can write files on a shared volume; it cannot directly set another container's environment. A future integration can read those files through Spring configuration or an application entrypoint. Existing Delinea, environment Secret and token-file support for external integrations remains available.

`values.yaml` and `application.yaml` include the supplied sanitized Delinea and Artifactory URL patterns. Secret ID `12345` and `REPLACE_WITH_*` repository/image paths are examples only: replace them and verify field slugs. These defaults do not make outbound requests at startup.

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

The chart mounts `application` as additional Spring configuration. Chart-owned command-line arguments for mode, loopback address, port and connection/configuration file paths take precedence. ConfigMap changes trigger a rollout. **Saved portal settings still override bootstrap connection mappings**; update those through the portal if a persisted configuration exists. Other startup changes require restart/rollout. Local runs can override `orchestrator.connection-defaults` using normal Spring configuration or continue supplying `--orchestrator.connections=PATH`.

Grafana/Loki URLs and per-namespace monitoring credentials remain documented integration contracts, not executable monitoring settings yet.

## Configuration defaults, live edits and import/export

All editable sections have a startup configuration location:

| Section | Application YAML | Helm values |
| --- | --- | --- |
| Artifactory, Secret Server, credentials, real image sources | `orchestrator.connection-defaults` | `connections` |
| Environments, services, mock image sources, scenarios | `orchestrator.catalog-defaults` | `catalog` |

Real catalog defaults include a labeled perf3 environment, load-generator service and baseline scenario example based on the supplied paths. Limits and unknown fields are illustrative, not approved execution settings. Simulation still loads its packaged mock catalog. An explicit `orchestrator.catalog` or `orchestrator.connections` file takes precedence over the corresponding inline defaults. Real service entries validate metadata, namespace references, dependencies and relative paths without requiring a local checkout. Simulation still validates project files and installation bindings. Remote Git retrieval and real execution remain unavailable.

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

In **Connections & catalog → Edit runtime configuration**:

- The source label identifies startup settings versus saved runtime JSON. The editor initially shows the complete document; section views are also available.
- **Save configuration** validates and applies all maps together immediately, then persists the complete configuration. Connection changes require a fresh vault sign-in.
- **Export active configuration** downloads JSON containing the active catalog and connection references. It excludes unsaved editor drafts and does not resolve passwords or tokens. Arbitrary values entered in the catalog are included: do not put plaintext secrets there, and review exports before sharing.
- **Import JSON…** loads an exported file into the Complete configuration editor, displaying the entire JSON document. Review the document or switch sections and click Save to validate/apply. Import replaces the complete configuration, must match simulation/real mode and is limited to 256 KiB. The destination's current edit revision is used; stale saves remain rejected.
- **Load startup defaults into editor** loads the startup snapshot for review. Saving it replaces runtime settings with that snapshot but still persists an override.

Saved runtime JSON has highest precedence over YAML/Helm, for the entire configuration rather than individual fields. It is created on the first save and survives restart. To return permanently to file-managed defaults, stop the app and move the saved configuration JSON to a backup location (keep the database), then restart. A new runtime configuration path is also an option for a local trial.

To import an export at startup, copy it to an ignored writable path and select it as the runtime configuration file:

```sh
./scripts/run-local.sh run --mode real -- --orchestrator.configuration-file=.local/imported-runtime.json
```

The file must already contain the complete exported JSON; if absent, startup defaults are used. Subsequent portal saves update this selected file. For CKP, an imported runtime JSON must be placed at the configured writable PVC path before startup; the chart does not provision exports onto the PVC. Keep exports separate from Spring YAML and Helm values—they use different document wrappers. Invalid imports are rejected rather than silently falling back to defaults.

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

For an office laptop without Codex, start with the [step-by-step real integration runbook](docs/integration/office-laptop-runbook.md), [minimal pilot configuration](docs/integration/examples/office-pilot-connections.yaml), and [sanitized support report template](docs/integration/support-report-template.md). The runbook separates the available read-only pilot from the adapters still needed for real E2E execution.

For pending organizational contracts, use the [integration questionnaire](docs/integration/organization-questionnaire.md) and [monitoring contract](docs/integration/monitoring-contract.md). Local implementation notes are in ignored `TASK.md`.

Outstanding organization inputs are tracked in [open integration questions](docs/integration/open-questions.md), including load YAML keys, lifecycle signals and metrics queries.

### AD sign-in prompt

Opening the real-mode UI prompts for AD credentials for a configured portal-mode Secret Server. Select the vault explicitly when multiple connections exist. Configure connections / Not now allow setup before authenticating. The password is cleared after submission; the backend exchanges it for a session-scoped token and returns status/expiry only. Expiry is tracked on the server and checked again before retrieving secrets; the UI schedules a sign-in prompt at expiry and rechecks when the tab becomes visible. Restart, expiry or connection edits can require sign-in again. No automatic password replay or token refresh is implemented.

This is vault authentication for on-demand secret retrieval, not shared-user authorization for hosting the portal publicly. Simulation does not show the initial prompt; environment/file token providers do not ask for AD credentials. Existing saved runtime JSON still supersedes new example defaults—load startup defaults into the editor to review them before saving.

# Performance Orchestrator

Prepare and run performance tests against configured CKP environments. The application checks out each service's CKP directory from Bitbucket, resolves image versions from Artifactory, prepares Helm charts, deploys services in the selected order, starts load generators, and queries Loki for traffic measurements and logs.

There is one application workflow. Simulation mode, generated demo builds, starter profiles, and synthetic load results have been removed. Deployment remains an explicit opt-in; browsing configuration and connection diagnostics does not deploy anything.

## Build and run

Requirements: Java 21+, Maven, and (for execution) Git, Helm 3 or 4, and kubectl available to the application process. Set `JAVA_HOME` to your JDK.

```sh
./scripts/run-local.sh build       # Clean, test and package
./scripts/run-local.sh run         # Run the existing JAR
./scripts/run-local.sh build-run   # Clean, test, package and run
```

Open http://127.0.0.1:8080. Stop with Ctrl-C. No `--mode` argument or application profile is needed. Extra Spring settings can follow `--`:

```sh
./scripts/run-local.sh run -- --server.port=8081
./scripts/run-local.sh run -- --spring.config.additional-location=file:.local/application.yaml
```

The application currently binds to loopback. CKP access uses port-forwarding; shared-user authorization is still required before exposing it through an ingress.

## Configuration and authentication

[`application.yaml`](src/main/resources/application.yaml) is the common local and CKP default configuration. Replace example URLs, secret IDs, repository mappings and API-server identities before using the integrations.

- `orchestrator.catalog-defaults`: environments, services, deployment destinations and scenario templates. Services define Git repository/chart paths, container image mappings and per-environment monitoring credential references.
- `orchestrator.connection-defaults`: Secret Server, Artifactory, Bitbucket, shared Loki endpoints and credential references. Multiple namespaces can use the same Loki endpoint with different secrets.
- `orchestrator.target-environment`: initially selected environment. Other configured environments remain available in the run builder.
- `orchestrator.execution`: deployment enablement, per-environment Kubernetes targets, Helm repositories, workspace location, command timeouts and run deadlines.
- `orchestrator.diagnostics.enabled`: enable command/API activity recording and the optional console.

**Connections & catalog** provides forms for live edits, plus JSON import/export. Saved field overrides take precedence over startup defaults on restart; unchanged fields inherit newer defaults. Restoring startup configuration removes those overrides. Deployment settings, server/database settings and scheduler settings require restart. Never put passwords or access tokens in configuration exports.

Secret Server accepts AD sign-in or a supplied access token. For AD sign-in, the password is exchanged for a vault token and retained encrypted in server memory for cluster login prompts until sign-out, session/token expiry, or restart. Bitbucket and Artifactory use access tokens supplied for the session or resolved through Secret Server. A token reference needs `provider: delinea`, `secretServerRef`, `secretId` and the actual `tokenFieldSlug`. Username/password secrets used for Loki instead specify their field slugs. See [connection details](docs/adapters/connections.md).

## Configure and run a test

First configure an approved cluster target and explicitly enable execution:

```yaml
orchestrator:
  target-environment: sandbox
  execution:
    enabled: true
    targets:
      sandbox:
        kube-context: sandbox-nvan
        expected-api-server: https://YOUR-APPROVED-API-SERVER:6443
      perf3:
        kube-context: perf3-nvan
        expected-api-server: https://YOUR-PERF3-API-SERVER:6443
```

Use the exact server from your kubeconfig. The process must inherit your working PATH, KUBECONFIG, login cache and network/CA configuration. On CKP use the configured service account/in-cluster target and approved RBAC. Commands specify the selected context; they do not change your global current context.

For local execution, selecting perf3 automatically targets `perf3-nvan`, even if your terminal currently uses qa. After Secret Server AD sign-in, recognized cluster username/password prompts are answered from the encrypted session. CKP receives the short username (`first.last` from `first.last@domain.net` or `DOMAIN\first.last`); the vault and audit identity keep the original username. Passwords are sent through stdin, never command arguments. Token-only vault sign-in cannot supply an AD password. The kubeconfig helper must support prompts through stdin/stdout; helpers requiring a terminal still need a prior local login.

1. Sign in to Secret Server, then verify Bitbucket references and Artifactory tags in connection diagnostics.
2. Open **Configure run**, select an environment, and add services in their deployment order.
3. Select Git references, image versions and CKP values files. Edit YAML if needed.
4. Choose the load-generator service and values file. The load tool's YAML controls its rate, destinations and runtime.
5. Configure monitoring panels using service, namespace, credential reference and LogQL. Save the profile and monitoring set for reuse.
6. Review the run. The application prepares chart versions/dependencies, validates rendered images and checks the selected cluster.
7. Confirm the prepared plan to deploy. Follow service/version progress, monitoring and optional command/API activity.

You can add independent load installations under a running run. Completion or cancellation cleans up all load releases owned by that run; service releases remain. Cleanup failures retain the environment reservation for recovery. Missing measurements produce an inconclusive performance verdict rather than a passing result.

Detailed procedures: [execution guide](docs/integration/real-execution.md), [office laptop setup](docs/integration/office-laptop-runbook.md), and [open organization questions](docs/integration/open-questions.md).

## CKP deployment

The chart is in `ckp/helm/ps-spoolers-perf-orchestrator`. Base `values.yaml` and `values-{sandbox,dev,qa,stable,perf,perf3}.yaml` configure the application instance. `catalog`, `connections` and `application.orchestrator` are rendered into ConfigMaps; saved dashboard overrides still take precedence for runtime-editable fields.

```sh
./scripts/run-local.sh build
docker build -f ckp/Dockerfile -t YOUR_REGISTRY/ps-spoolers-perf-orchestrator:0.1.0 .
# Publish through your organization's approved process, then set image.repository/image.tag.
./scripts/deploy-ckp.sh render --namespace YOUR_NAMESPACE --values ckp/helm/ps-spoolers-perf-orchestrator/values-sandbox.yaml --values .local/ckp-values.yaml
./scripts/deploy-ckp.sh deploy --context YOUR_CONTEXT --namespace YOUR_NAMESPACE --values ckp/helm/ps-spoolers-perf-orchestrator/values-sandbox.yaml --values .local/ckp-values.yaml
kubectl --context YOUR_CONTEXT --namespace YOUR_NAMESPACE port-forward deployment/ps-spoolers-perf-orchestrator 8081:8080
```

Configure persistent storage, image-pull credentials, cluster permissions and CA trust for your environment. This deployment script installs the orchestrator itself. It does not start a performance test. The chart retains its PVC on uninstall.

## Existing installations

The existing `data/real/` paths are retained deliberately: the H2 database, runtime overrides, artifacts and workspaces keep their current locations. Back up persistent state before upgrading. Finish active runs before upgrading and prepare fresh reviews afterward because the plan schema/checksum has changed. Previously saved production profiles and run history remain readable; obsolete mode fields are ignored when importing them.

Historical Flyway migrations remain unchanged. A new migration drops the obsolete simulator tables and simulator profile tables; production profiles, runs, plans, events and monitoring remain. Old simulation databases are not supported as production data. This change does not delete your local `data/` directory.

Remove `--mode`, `orchestrator.mode`, `spring.profiles.active=real` and Helm `mode` settings from launch/deployment overrides. Execution APIs now use `/api/v1/execution/...`; run history, reports, cancellation and configuration stay under `/api/v1/...`. Reload the browser after upgrading.

## Development and verification

The backend uses Spring Boot, H2 and Flyway. The frontend uses JavaScript modules and a locally bundled CodeMirror YAML editor; Node is not needed to run the application. For UI maintenance use `npm ci`, `npm run build:editor` after editor changes, and `npm run format:check`.

Tests use scenario Javadoc with GIVEN/WHEN/THEN and imported `@DisplayName`. Preserve meaningful coverage for authentication, configuration, preparation, deployment, cancellation, cleanup and recovery. Prefer constructor injection and `final` bindings; keep mutable lifecycle state explicit. Test doubles and test-only fixtures remain under `src/test`; they never provide an application runtime mode.

The test configuration is `src/test/resources/application-test.yaml`; it uses isolated databases and sanitized endpoints. Tests do not contact office services or deploy to CKP. The [API document](src/main/resources/static/openapi.json) describes the HTTP surface; mutations require the session cookie and CSRF token from `/api/v1/session`.

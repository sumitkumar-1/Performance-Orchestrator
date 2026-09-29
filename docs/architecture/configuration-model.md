# How configuration maps to a run

## Environment: where the run happens

A target environment (for example `perf3`) selects a cluster identity, load-generator namespace, allowed actions, and load limits. Each selected service then resolves its own destination from `deploymentByEnvironment["perf3"]`.

| Selection | Resolved configuration |
| --- | --- |
| Target environment = perf3 | Cluster, load-generator namespace, limits and permissions |
| Service = auth-service, target = perf3 | Auth namespace, Helm release name, ordered values files |
| Auth image source = dev | Auth's dev image repository and installation source selector |
| SMTP image source = release | SMTP's release image repository and installation source selector |

The image source does not choose environment values. Both services can target perf3 while using different build sources. `serviceNamespaces` is an allowlist; it is not a shared destination namespace. All current cluster identities and namespaces are simulation fixtures. `stging` remains unchanged pending confirmation.

The editor shows target information beneath the environment selector, each selected deployment shows its namespace/release, and the deployment dialog shows exact values-file paths. Changing environments preserves image choices and re-resolves destinations. An unavailable mapping is shown as an error and blocked by backend planning.

## Service catalog: how a service is installed

The current executable `src/main/resources/mocks/catalog.yaml` holds service names, local fixture `projectPath`, dependencies, installation bindings, allowed overlay paths, and per-environment namespace/release/values mappings. The Add deployment dialog consumes `/api/v1/services`, so a future remote catalog provider can supply the same data without changing the user workflow. Adding a deployment selects a catalog service; it does not register a new service.

Namespace and release name are configurable. Values-file conventions can be standardized in the catalog, but explicit paths remain the execution contract so services with a different layout or extension do not silently use the wrong file.

Current service projects do **not** contain Git/bucket URLs and no remote project is fetched. For real integration, each service should reference an approved configuration source with:

- Source type (Git or a specific supported object store), URL, and credential reference.
- Git ref resolved to a commit, or bucket object version plus checksum.
- Chart/config root and exact base/environment values paths.
- Namespace, release name and installation bindings.

Resolve/fetch those files while creating the immutable plan, then deploy that exact prepared snapshot. Fetching a moving branch or latest object only at deployment time would make the reviewed plan unreliable. Git/bucket providers, URL rules and credential methods will be implemented once the actual source contracts are supplied; no speculative source fields are accepted by the current simulation schema.

## Artifactory and Delinea: separate connections

`docs/integration/examples/connections.yaml` defines distinct URLs and independent authentication:

1. The app accesses the configured **Delinea URL** using its own externally supplied bearer token (for example the environment reference `DELINEA_ACCESS_TOKEN`). This bootstrap credential must come from an approved source outside the secret it unlocks.
2. A named credential reference identifies a Delinea secret ID and username/password field slugs.
3. An **Artifactory URL** points to its own credential reference. The app retrieves that secret and uses the resulting credential only for the configured Artifactory connection.

Several Artifactory servers can use different secrets. Several Delinea servers can each have a different token environment reference. Credentials may also be provided through local environment references for development. No password or token value belongs in the catalog or browser.

The profile editor currently uses explicitly labeled simulated builds. Configured real JFrog discovery is separate in Connections & catalog, pending confirmed installation bindings and deployment contracts. See `docs/adapters/connections.md` for the implemented read-only APIs and current Delinea bearer-token requirements.

## Grafana metrics and logs

The environment catalog currently accepts a `dashboardUrl` for links. Direct metrics/log retrieval is not implemented yet. When queries are supplied, also identify the datasource/API, time-range and namespace/service variables, response format/units, and read-only credential reference. Grafana may front different metrics/log systems; the backend adapter will target the approved API rather than assume Prometheus or Loki.

The intended panel will query from the backend with bounded polling and the run's measurement window, show freshness and missing-data status, and never expose credentials or unrestricted query access to the browser.

## Resource layout and live updates

The bundled mock catalog, starter profile, and project files live in `src/main/resources/mocks/`. The single `src/main/resources/application.yaml` contains startup defaults and its real-profile section. Real catalogs start empty. Connection defaults use sanitized organization URL patterns and an explicitly illustrative secret ID in application.yaml; no image sources are enabled by default. Optional bootstrap examples live under `docs/integration/examples/`; validation schemas remain under resources. The resource reader supports classpath resources in packaged JARs and administrator-selected external files.

The Settings editor edits individual catalog or connection sections and submits one complete document with a revision to `/api/v1/configuration`. The backend validates cross-references, supported modes, required fields, dependency cycles, project paths, environment allowlists, limits and connection authentication before persisting or publishing. Invalid updates, write failures and stale revisions leave the prior configuration active. A request-wide read/write lock prevents an API operation from combining configuration revisions. File replacement is atomic; persisted overrides load before seeding a starter profile.

Runtime changes do not mutate persisted plans or active runs. New run submissions reject old catalog hashes, while accepted runs use their immutable prepared inputs. Updating connection mappings expires portal sessions locally to prevent using a token with a changed destination. This remains a local-only administrative UI; shared administration requires authentication/tenant authorization.

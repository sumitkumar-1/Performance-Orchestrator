# Real services integration — organization questionnaire

> Authentication update: the AD/password-exchange details below are historical reference only. The application now accepts supplied Bearer access tokens for Secret Server, Artifactory and Bitbucket. No AD login is supported. See [current connection configuration](../adapters/connections.md).

Connection details received on 2026-09-17 are recorded in [the connection notes](confirmed-connection-details.md), together with unresolved path/authentication details. Use those answers when completing this worksheet. The subsequent [monitoring and provisioning contract](monitoring-contract.md) records portal sign-in, future init-container tokens, namespace secret IDs, and the supplied LogQL/PromQL examples; these do not need to be supplied again.

Use this document to collect the technical contracts and approvals needed to connect the Performance Environment Orchestrator to organizational systems. It is an answer worksheet, not an executable configuration file.

Start with **one service, one permitted non-production environment and one small load scenario**. Expand to two services with different namespaces and image sources after the first path works.

For unanswered items, write **Unknown**, the team/person who can confirm, and any expected follow-up date. Write **Not applicable** for conditional features you do not use. Separate confirmed answers from proposals.

Provide sanitized files and examples through an approved channel. Include credential references, secret IDs or field names where permitted; do not put actual passwords, bearer tokens, private keys, complete kubeconfigs, or sensitive test/customer data into this document. Preserve relevant command structure, YAML keys, value types and API response shapes when sanitizing.

## What is already established

- The backend is Java, with a simple browser UI.
- Services are deployed using an existing Helm-based CKP workflow.
- Each service has its own project-owned `ckp/` inputs and environment-specific namespace mapping.
- The shared application is intended to run in its own CKP namespace.
- Image source is selected independently for each service, independently of the target environment.
- Artifactory and Delinea Secret Server have separate URLs and authentication. Each Artifactory connection can reference a different credential.
- Deployment permissions differ by user/environment, especially perf3/stg1.
- Grafana already displays organizational metrics/logs, including a perf3 dashboard. Specific queries can be supplied later.
- The current app executes simulations. Its separate JFrog/Delinea read-only adapters have not been validated against organizational servers. Git/bucket fetching, real Helm/load execution, direct telemetry, and shared authentication are not implemented yet.

The questions below ask for exact contracts and mappings, rather than asking you to reconsider these established choices.

## Priority and suggested owners

| Stage | Information needed | Suggested owners | What it enables |
| --- | --- | --- | --- |
| A — Read-only connections | Pilot selection, networking, Delinea authentication, Artifactory mappings | Service owner, secrets team, registry team | Authenticate, list real builds and resolve digests without changing CKP |
| B — Real planning | Project sources, exact files, Helm/source/version bindings, environment mappings, inspection access | Service owner, platform/CKP team | Fetch and pin real inputs, render and inspect an execution plan |
| C — First real run | Approved execution identity, readiness, load lifecycle, limits, stop/cleanup and success criteria | Platform team, service owner, performance team | Deploy and run a small test in the approved target |
| D — Shared CKP release | SSO, target authorization, hosting, PostgreSQL, shared artifacts, operations | Identity team, platform team, DBA/storage owners | Expose the application for team use |
| E — Live monitoring | Grafana/data-source API, query templates, credentials and response contracts | Observability team | Show live metrics/logs and evaluate any thresholds that depend on them |

Stages A/B can start before all later answers are available. Stage D is required before shared team exposure, not optional hardening after deployment. Stage E may follow the first run unless its measurements are required for the agreed verdict. Mutation still requires an explicitly permitted execution location and identity, even for a pilot.

## 1. Pilot scope and contacts

**Needed for:** all stages. **Likely owner:** service/performance lead.

1. Which service should we integrate first? Provide its exact service identifier and technical owner. Do not normalize similar identifiers such as `router-delivery-agent` and `route-delivery-agent-clap` without owner confirmation.
2. Which environment is approved for the first deployment/load test? Which actions are allowed there: inspection, deployment, load execution, cancellation, cleanup, or restoration?
3. Where may the pilot app execute: an engineer's machine, its CKP namespace, or an approved runner? Which account may perform the test, and who can authorize the initial integration trial?
4. What is the smallest useful load scenario, and what observable result would make this pilot successful?
5. Which second service should we use to verify independent sources, separate namespaces and dependency ordering?
6. Who should resolve questions for CKP/Helm, Artifactory, Delinea, Git/object storage, load-gen, identity, and Grafana?

**Answer:**

- First service / owner:
- Pilot target / allowed actions:
- Execution location / identity / approver:
- Scenario / expected result:
- Second service:
- Team contacts:

## 2. Network access and trust

**Needed for:** A–D as each endpoint is connected. **Likely owner:** network/platform team.

1. From the pilot location and future hosting namespace, can the app reach Delinea, every selected Artifactory instance, configuration repositories/buckets, target cluster API, load-gen control endpoint, PostgreSQL, artifact storage, SSO and approved monitoring APIs?
2. What are the approved endpoint hostnames and ports? Are endpoint paths different between local/VPN access and CKP access?
3. Are VPN, corporate proxy, `NO_PROXY` rules, DNS configuration or egress allowlists required? Who provisions them?
4. Are private certificate authorities, client certificates or mutual TLS required? How should trusted certificates and rotating client credentials be delivered to the JVM/container?
5. Are there connection-rate limits, expected slow responses or maintenance windows that should affect polling/retry limits?

**Provide:** an endpoint reachability matrix, approved CA distribution instructions, and any proxy/network-policy requirements. Certificate validation must remain enabled.

| System / logical connection | Approved hostname and port | Reachable from pilot? | Reachable from hosting namespace? | Proxy / CA / allowlist owner |
| --- | --- | --- | --- | --- |
| Delinea | | | | |
| Artifactory instance 1 | | | | |
| Git or bucket | | | | |
| Target cluster API | | | | |
| Load-gen control | | | | |
| Grafana / data source | | | | |

## 3. Delinea Secret Server authentication and secret retrieval

**Reference code now available:** the supplied client posts form-encoded `grant_type=password`, `username`, and `password` to the token endpoint, then uses the returned token to retrieve secrets. See [reviewed connection details](confirmed-connection-details.md#delinea-secret-server). Questions about request mechanics below can use this reference; remaining answers should confirm its applicability to this app, bootstrap credential delivery, exact secret fields, and token lifecycle.

**Needed for:** A. **Likely owner:** secrets/security team.

1. Is this Secret Server on-premises, Secret Server Cloud, or access through Delinea Platform? What deployed version and API base URL/version should we use?
2. What non-interactive authentication mechanism is approved for this application: externally issued bearer token, an OAuth flow, SDK/workload identity, or another supported method? Provide the approved documentation or a sanitized working request sequence.
3. How does the app obtain its **initial Delinea credential**? Who provisions it, where is it stored, and how is it delivered locally and in CKP? This bootstrap credential must not depend on retrieving the same secret it is meant to unlock.
4. If tokens must be acquired/refreshed by the app, what are the exact token endpoint, grant, audience/scope, request encoding and response fields? What are token lifetimes, refresh rules and revocation behavior? Do not include actual client secrets or tokens.
5. Which application identity may retrieve which secret IDs/folders? Can it retrieve the relevant secrets unattended, or are approval, MFA, checkout, reason/comment or session requirements involved?
6. What endpoint retrieves a secret? Provide a sanitized success response and denied/expired-credential response. Does the response contain `items`, `slug` and `itemValue`, or a different structure?
7. For each secret template, which exact field slugs contain the username and password/token? Is an Artifactory access token stored as a password field or elsewhere?
8. How should secret rotation be handled? May values be cached, for how long, and what should happen when a credential expires during a run? Are checkout/check-in or retrieval auditing requirements applicable?
9. Will multiple Delinea instances or tenants be needed? If so, give each a separate connection identity and authentication reference.

**Current adapter to validate:** the app uses a portal-session or externally provided bearer token, retrieves `{configured-api-base}/secrets/{secret-id}`, and reads configured field slugs from `items[].itemValue`. Portal password-grant token acquisition and external environment/file tokens are now supported. Automatic refresh, tenant authorization and background-run delegation are not implemented. Validate response fields and credential mappings against the pilot.

**Provide:** sanitized authentication/retrieval examples and this mapping:

| Credential reference in app | Delinea connection | Secret ID/reference | Username field slug | Password/token field slug | Authorized destination/use |
| --- | --- | --- | --- | --- | --- |
| | | | | | |

## 4. Artifactory instances, repositories and build discovery

**Needed for:** A; installation mappings also feed B. **Likely owner:** registry/build team.

1. List each Artifactory base/API URL, its deployed version, and the credential reference used to access it. Artifactory authentication must be independent of the Delinea access token.
2. What authentication does each instance accept for the approved discovery interface: username/password, username/access-token, bearer token, registry token challenge, or another mechanism?
3. Which discovery API is approved: Artifactory Docker V2 tag/manifest APIs, another registry interface, AQL, or an internal build-catalog service? Provide a sanitized working request and response, including pagination and relevant headers.
4. For each service and source category—dev, QA, stable, release, user builds—what are the exact instance, repository key and image path? Are the repositories local, remote or virtual, and does that affect discovery?
5. For user-specific builds, how does the entered artifact-owner username map to the repository/path? What characters/case are allowed? Is the artifact owner independent of the authenticated reader? Which users may browse whose builds?
6. What distinguishes the discovery API URL from the **image pull reference** used by CKP? Provide one actual sanitized pull reference for each pilot source.
7. Are tags mutable? Are moving aliases such as `latest` present? Which tags are selectable, and how should filtering/order work? If publication time/build metadata matters, which API supplies it?
8. How are manifest digests returned? Are there multi-architecture image indexes, and which operating system/architecture does the target use? Can the chart deploy an immutable digest?
9. Do target namespaces already have image-pull access to the chosen repositories? Who owns pull-secret provisioning/rotation? Registry browsing credentials do not automatically grant pods pull access.
10. Are images usable directly, or must they be promoted/copied to another registry before deployment? If promotion is required, identify its existing approved workflow and owner rather than assume the orchestrator may copy images.
11. Are discovery permissions caller-specific or shared under an application reader account? What rate limits, page-size limits and private-result caching restrictions apply?

**Current adapter to validate:** configured Artifactory Docker V2 tag pagination plus manifest retrieval and SHA-256 verification, using Basic authentication over HTTPS. Other auth/discovery methods require adapter work.

| Service | Source label | Artifactory connection | Repository key | Image path/template | Pull reference pattern | Credential reference |
| --- | --- | --- | --- | --- | --- | --- |
| | dev | | | | | |
| | release | | | | | |
| | user | | | | | |

**Provide:** two discoverable pilot image versions and sanitized tag-list/manifest responses. Include the same version string in two sources if that occurs in practice.

## 5. Service project sources: Git, bucket or packaged chart

**Needed for:** B. **Likely owner:** service/repository owner.

1. For each pilot service, where are its deployment inputs stored: Git, an object-storage bucket, an OCI/chart repository, or another approved location? Provide the URL and repository subdirectory or object prefix.
2. For Git, which ref should be selected by default, and may it be resolved to a commit at planning time? Are submodules, Git LFS, generated files or private chart dependencies involved?
3. For a bucket, which provider/API, bucket, region/endpoint, object key and archive format are used? Is object versioning enabled? How can we pin an immutable object version and verify its checksum?
4. What read-only identity and credential reference should fetch these inputs? Are SSH keys, HTTPS tokens, cloud roles or signed URLs required? What is the approved access route from CKP?
5. What are the exact paths for chart root, deployment templates, base values, target-environment values, chart dependencies and any current installation scripts? Is a path named `deployment` a chart, a template directory or a raw manifest?
6. Are values-file names/layouts standard across services? Identify exceptions and `.yaml` versus `.yml` extensions. Provide ordered values-file paths rather than relying on guessed filenames.
7. Does the desired image build require a matching source/config commit or chart version? How is that association discovered and validated?
8. Which catalog fields may administrators change, and who owns reviewing new service registrations? Will the initial catalog be configuration-backed, or is there an existing service directory API we should use later?

**Provide:** a sanitized directory tree and one complete representative `ckp/` input set, including its current install script/command and referenced schemas/dependencies.

**Proposed behavior to confirm:** fetch and pin the project during planning; prepare isolated copies; deploy the exact captured inputs. Never alter/push to the original service repository or fetch a newer moving ref during execution.

## 6. Environment and service destination mapping

**Needed for:** B/C. **Likely owner:** CKP/platform team and service owners.

1. Confirm exact environment identifiers and aliases. In particular, is `stging` intentional, or should it be a separately confirmed `staging` entry?
2. For each environment, what are the cluster identity, connection/context reference and load-generator namespace? Which environment names refer to the same physical cluster or shared resources?
3. For each service/environment pair, what are the namespace, Helm release name and ordered values files? Which pairs are unsupported?
4. Are service/load namespaces pre-created? Who owns their provisioning, quotas, service accounts, network policies, pull credentials and permissions?
5. Which actions are allowed in each target: plan/inspect, deploy, verify existing, run load, stop, collect logs, clean up owned load, restore previous versions? Are there restricted time windows or explicit approval requirements?
6. What approved endpoints should load-gen use for each service? Provide cross-namespace DNS/ingress/service routing and authentication requirements; do not assume short service names work from the load namespace.
7. May tests run simultaneously in different environments on the same cluster? Which environments share infrastructure and must be mutually exclusive? Is one active mutating run per environment a suitable initial policy?

| Environment ID | Cluster / connection reference | Load-gen namespace | Allowed actions / approval policy | Shared contention group, if any |
| --- | --- | --- | --- | --- |
| | | | | |

| Service | Target environment | Service namespace | Helm release | Ordered values files | Load-gen endpoint |
| --- | --- | --- | --- | --- | --- |
| | | | | | |

**Example interpretation:** auth-service from the dev image source and smtp-receiver from release may both target perf3. Each uses its own perf3 namespace/release/values mapping. Image source does not choose `values-dev` or `values-release`.

## 7. Exact Helm workflow and image/source bindings

**Needed for:** B/C. **Likely owner:** service deployment/CKP team.

1. Supply one full, currently working sanitized Helm command, the working directory, required environment-variable **names**, wrapper script if used, and exact Helm/Kubernetes versions. Which flags are mandatory organizational policy?
2. Who owns the release today: direct Helm, GitOps, an internal deployment tool or a pipeline? Is the app permitted to call Helm directly, or must it submit to that owner/runner?
3. What chart source/version/dependency lock is used? Are chart dependencies already vendored, or must they be fetched during planning?
4. Exactly which file and YAML field/template location contains `REPLACE_VERSION`? What is the expected occurrence count? Does it represent app version, chart metadata, image tag or multiple distinct values?
5. What transformations convert a discovered image tag into each required installation version? For example, is a chart-compatible version different from the displayed build tag? Provide explicit examples, not inferred rules.
6. Which exact Helm arguments/values keys carry image/application version? Does `--version`, if present, refer to the chart version? Which inputs need string typing to avoid YAML/Helm coercion?
7. What is the exact build-source selector key/variable, file/location and type—string, boolean or another type? Map dev, QA, stable, release and username sources to supported selector values separately for each service.
8. Which repository/tag/digest fields control each application container and any sidecars? Can the rendered image be verified against the selected source and immutable digest? If not, what approved chart change is needed?
9. Are hooks, CRDs, cluster-scoped objects, post-renderers, plugins, secrets, additional values files or post-install steps involved? Which are allowed, and which must block planning?
10. Which inspection/rendering steps are approved for read-only planning? Are server-side validation or cluster admission checks available, and under which identity? Which preflight checks are mandatory before deployment?
11. What are normal and maximum deploy/readiness times? What should happen after partial deployment, an existing pending Helm operation, or a timeout with an uncertain result?

| Service | Source category | Source-selector key and type | Exact selector value | Version transformation | Helm version-bearing input |
| --- | --- | --- | --- | --- | --- |
| | dev | | | | |
| | release | | | | |

**Provide:** the command/script plus before-and-after snippets showing token substitution, source selector and final image. Preserve booleans versus strings when sanitizing. Do not include credential values or raw secret manifests.

## 8. Execution identity, readiness and service dependencies

**Needed for:** B for inspection; C for mutation. **Likely owner:** platform/security and service owners.

1. What identity will actually inspect/deploy/stop resources for each target: scoped app service account, delegated user identity, or approved runner/pipeline? How are credentials obtained, rotated and restricted to the right cluster/namespaces?
2. How do we independently verify the requesting user's right to use that execution identity? For perf3/stg1, what exact user/group rules or approvals apply? An app service account's permissions must not automatically become every user's permissions.
3. Which workload kind, name and labels identify each service? What confirms readiness: desired rollout revision/generation, ready replicas, expected image identity, health endpoint and/or smoke test?
4. What are the health/smoke endpoints, credentials, expected responses, retry policy and deadline? Are any dependencies outside Kubernetes required for these checks?
5. What service dependencies and startup order are required? Which services may be `VERIFY_EXISTING`, and what exact image/version must be verified? Are there declared release bundles or compatibility constraints?
6. Can deployments occur outside this app while a test runs? How should the app detect drift, and should a changed environment invalidate the performance result?
7. After a lost connection or process restart, how can the adapter look up the existing deployment operation and confirm its outcome without submitting it again? Does the runner/platform support idempotency IDs or fencing against an old worker?

**Provide:** a minimal namespace-scoped permission matrix, representative workload status/health responses, and one approved readiness success/failure example.

## 9. Load-generator lifecycle and scenario contract

**Needed for:** C. **Likely owner:** load-generator/performance team.

1. What is the load generator and version? Is it launched as a Job, Deployment, standalone process, API operation or pipeline? Provide the exact existing launch mechanism rather than assume Kubernetes Job semantics.
2. Where are its image/chart and reusable scenario files stored, and how are their versions pinned? What are the configuration schema and parameter types?
3. Supply one small working scenario and one sanitized customized scenario. Which fields map to virtual users, request rate, ramp-up, warmup, measured duration, target endpoints and credentials?
4. Which overrides should users be allowed to edit, and which fields must be generated/protected by the app? Are credentials injected through secret references, mounted files or another approved mechanism?
5. How does launch return a durable operation/resource ID? Can a caller provide a run ID/idempotency key? After an uncertain launch response, how can we find the existing operation before retrying?
6. How are pending, running, successful, failed and complete states detected? Does process exit mean the remote load is finished, or is another status check required?
7. What is the exact stop/cancel operation? How do we verify traffic actually stopped, including after disconnects or app crashes? Is there a remote deadline/TTL that stops orphaned load independently of the app?
8. Where are results and logs stored? Supply sample paths/API responses, file formats, exit/status codes, units and any authoritative aggregate summary. Are partial results available after cancellation/failure?
9. What namespace, service account, CPU/memory, node architecture, network connectivity, certificates and per-target access does load-gen require?
10. Does the scenario require tenants, test accounts, seeded/reset data, queues, external services or other setup? Who performs setup/cleanup, and can the data safely be reused for repeated runs?

**Provide:** launch → status → stop → results examples, a scenario configuration, and a sanitized successful/failed results sample. These are the minimum lifecycle artifacts needed to automate a real load run safely.

## 10. Limits, verdicts, cancellation and cleanup

**Needed for:** C. **Likely owner:** performance/service/platform owners.

1. What are the permitted maximum users, request rate, warmup/measurement time, total duration, CPU/memory and concurrency for the pilot target? Are quotas/autoscaling/replica counts fixed or variable during a test?
2. What defines a passing test? For each threshold, give metric name, unit, aggregation, comparator/value, measurement window and whether missing data is blocking or inconclusive.
3. How is an error counted, and what is its denominator? Are retries, timeouts, client errors and warmup requests included? Which result source owns aggregate latency percentiles?
4. Is missing/no-traffic data inconclusive? If no thresholds are agreed yet, may the first run report execution success with performance INCONCLUSIVE rather than imply a pass?
5. Should deployed service versions remain in place after testing? The proposed default is **KEEP**. If restoration is required, who authorizes it, which pre-run revisions must be captured, and how must intervening external changes be protected?
6. After one service deployment fails, should the app stop without deploying further services? What evidence and partial cleanup must still be collected?
7. Which load resources are owned by the run and may be removed? What ownership labels/IDs are available? How long should completed/failed resources remain for investigation?
8. When cleanup or stopping cannot be confirmed, who resolves it? Should the environment stay blocked until verified safe? What escalation/recovery information must the UI show?

| Metric | Source | Unit / aggregation | Threshold / comparator | Measured window | Required for verdict? |
| --- | --- | --- | --- | --- | --- |
| | | | | | |

## 11. Grafana, real-time metrics and logs

**Reference code now available:** logs use direct Loki `/query_range`, Basic authentication with Delinea credentials mapped per namespace, and configured LogQL templates. See [reviewed connection details](confirmed-connection-details.md#loki-logs). Confirm the pilot mappings and supply the query template; a separate metrics contract is still needed. The reference code does not establish that a performance run ID is interchangeable with its SMTP correlation/tracking ID.

**Needed for:** E; C only if required for verdicts. **Likely owner:** observability/performance team.

1. What are the dashboard URLs for each environment, including perf3? How should environment/service/time-range variables be supplied in links?
2. Which data sources power those dashboards? Should the app query Grafana's approved API/data-source proxy or a source API directly? Provide the exact endpoint/version and supported response contract.
3. What read-only identity and credential reference may query metrics and logs? Are data-source IDs/UIDs, organization IDs, tenant headers or query permissions required?
4. Provide each approved query with its variables and an example response. How do service, namespace, environment, pod/container and run identity map to labels? If traffic cannot be isolated by run ID, what other traffic may be included?
5. Which signals matter initially: throughput, error rate, p50/p95/p99, CPU/memory, restarts, saturation or recent logs? Which are display-only and which feed thresholds?
6. What polling interval, query step, lookback, ingestion delay, maximum time range, series/cardinality limit and log-line/byte limit are acceptable?
7. How should timestamps/time zones, units and percentiles be interpreted? Should warmup be excluded? Is there an authoritative aggregate rather than averaging independently computed percentiles?
8. Which fields/log content must be redacted or access-restricted? May results be stored in reports, and for how long?
9. If monitoring is unavailable, should a run continue when those metrics are optional? What stale/unavailable status should the UI display?

**Provide:** a small initial query pack—ideally throughput, error ratio, p95 latency and recent logs—with variable mappings, response samples, units and credential references. Dashboard links can be enabled before direct querying. Embedding is optional and must follow existing authentication/frame policies.

## 12. Shared CKP hosting, SSO and operations

**Needed for:** D, or earlier if the pilot is shared/hosted. **Likely owner:** identity/platform/DBA/storage teams.

1. What hosting cluster and dedicated namespace, container registry, ingress hostname/class and TLS setup are approved? What Java baseline, base image and build/signing/scanning requirements apply?
2. Which organizational identity provider/protocol should authenticate browser users? Provide issuer/metadata URL, client registration process, callback requirements, session/logout policy and claims/group mapping. No client-secret values are needed in this worksheet.
3. Define viewer/operator/administrator capabilities separately from environment permissions. Who may inspect artifacts/logs, edit profiles/catalogs, deploy, run load, cancel another user's run or restore releases? Who manages these grants?
4. What execution route and credential provisioning are approved for each target from the hosting namespace? Can the hosted app reach all target APIs and required services?
5. What PostgreSQL version, database/schema, authentication reference, TLS, connection limits and migration ownership are provided? Who handles backup/restore and recovery targets?
6. What durable artifact storage is approved: shared volume or object storage? Supply access method, identity, encryption requirements, retention and backup policy. Are separate retention/access rules needed for logs versus reports?
7. How many API instances/workers are initially allowed? Is a single active worker acceptable? What shared lease/fencing mechanism is approved for cluster/environment contention and crash recovery?
8. What probes, CPU/memory requests/limits, security context, network policies and service-account restrictions are required for the app's Helm chart?
9. Which actions must be audited, where must audit records go, and what retention/access policies apply? What event/diagnostic data may be exported?
10. Who owns installation, upgrades, credential rotation, incident response and stale-run recovery? What availability, recovery, capacity and report-retention expectations apply?

## 13. Integration acceptance and handover

**Needed for:** scheduling each stage. **Likely owner:** pilot owner plus relevant platform/security reviewers.

1. Which approved disposable/non-production targets and time windows can integration tests use? Which external actions are explicitly permitted during those tests?
2. Who will verify the discovered image list, rendered plan, namespace/release mapping and actual deployed image for the first service?
3. Who will verify that load starts only after readiness, cancellation stops traffic, and reports use the correct measured interval and units?
4. Can we demonstrate denied access, unavailable image, readiness failure, duplicate submission, restart after load launch, missing metrics and failed cleanup? Which cases require coordination with platform owners?
5. What evidence/sign-off is required before expanding to multiple services or restricted targets such as perf3/stg1?
6. What final setup/runbook, audit/export/report formats, backup/recovery demonstration and operational handover are required?

## Minimum package to return first

You do not need to answer every section before we resume. The most useful first batch is:

1. **Pilot selection:** one service, one approved environment, execution location/identity and technical contacts.
2. **Delinea:** API base/version, approved authentication flow, bootstrap credential-delivery method, secret reference and field slugs, sanitized retrieval response.
3. **Artifactory:** one URL, credential reference, repository/image path, approved discovery/auth mechanism, sample tag/manifest response and image pull reference.
4. **Project:** source URL/type/access reference and a sanitized `ckp/` tree with chart/values files.
5. **Deployment:** working Helm command/script, versions, namespace/release/values mapping, exact `REPLACE_VERSION` and build-source/version bindings.
6. **Load:** one scenario plus launch/status/stop/result examples, bounds and initial success criteria.

Items 1–3 unblock validating live read-only discovery. Items 4–5 unblock real planning. Item 6 plus approved execution/readiness/cleanup contracts unblock implementing the first real run. Shared hosting/SSO answers are also mandatory if that run will be exposed through a team deployment. Grafana queries can follow unless used for required thresholds.

## Answer tracking

| Section / question IDs | Confirmed answer or attachment | Owner | Status: confirmed / proposed / unknown / N/A | Follow-up date |
| --- | --- | --- | --- | --- |
| | | | | |

**Pilot decision record**

- First service and environment:
- Authorized execution location/identity:
- Approved read-only connections:
- Approved mutation scope and test window:
- Credential provisioning owner (references only):
- Remaining blockers for discovery:
- Remaining blockers for planning:
- Remaining blockers for first run:
- Remaining blockers for shared release:
- Optional monitoring items deferred:

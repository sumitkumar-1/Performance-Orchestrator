# Open organization integration questions

Updated 2026-09-29. Confirmed: monitoring uses Loki/LogQL for logs and log-derived metrics, with shared Loki connections referenced by environments and credentials selected per service/environment. Supply concrete query/response examples next. Fill in answers here; do not include passwords or tokens.

## Load-generator chart and profile

- Which exact YAML keys control requested messages/second, duration, message count and destinations? What are their types, units, defaults and allowed ranges?
- How do duration and message count interact if both are configured? Which fields may portal users edit?
- Supply a sanitized baseline values file and relevant chart templates/schema. Is `clustersubdomina` the actual key?
- What are the load-generator repository slug, selected tag/ref, chart path and profile directory? Are profiles in a separate repository?
- Does upgrading the existing release restart generation, or must a workload be explicitly restarted? What happens if the same inputs are submitted again?

## Stop and completion

Confirmed: the tool has a configured runtime; operators can scale pods to zero or uninstall the release to stop traffic.

- Which controller owns the pods (Deployment, StatefulSet, Job, other), and what exact resource name/selector should be scaled? Pods themselves are not scalable controllers.
- Can an HPA/operator restore replicas? How do we verify generation actually stopped?
Confirmed: Stop uninstalls only the load-generator release created by the run; service releases remain.
- How does the application report natural completion? Does the process exit, expose a status/metric or continue running idle?
- How long should downstream processing drain after generation stops? What evidence/results must be collected before uninstalling?
- Can another person/run already own the same release? What authorization and exclusivity rules apply?

## Metrics and namespace credentials

Confirmed: the Java load generator exposes actual generation-rate metrics; configuration specifies the desired messages/second. Every namespace has its own secret.

- What metric names and labels identify generated, received, processed and failed messages for load-gen, SMTP-R and RDA? Supply one working query and sanitized response for each.
- Loki/LogQL is confirmed. Provide the shared Loki API bases and environment assignments, plus working log-derived rate/count queries and sanitized responses.
- Which secret ID/reference and username/password field slugs belong to each `(environment, namespace)`? Are tenant headers required in addition to the shared logs/LogQL-metrics credentials?
- What are log emission interval, expected ingestion delay and outcome meanings? Are counters per message, attempt, recipient or batch?
- Can a run-specific label isolate traffic? If not, is the namespace dedicated during the test?
- What are acceptable throughput/error thresholds and the completion criteria?

## Source retrieval and CKP

Confirmed browser repository pattern: `https://stash.domain/projects/SP/repos/{projectName}`. This is a web URL, not yet a confirmed Git clone URL.

Implemented: discover the HTTPS clone link from Bitbucket repository metadata and use the configured token. Confirm repository mappings and one working selected revision in sandbox.
- Should selectable refs be tags only, or also branches/commits? We will refresh the selected ref for each new deployment preparation, record its resolved commit, and execute that pinned snapshot. A moved tag requires a new preparation rather than changing an already reviewed run.
- Provide explicit dev/perf kube-context, execution identity, service chart paths, namespace/release mappings and readiness criteria.
- How are private chart dependencies/submodules fetched? Supply approved credentials/references if needed.
- Which background identity may retrieve secrets during long runs, and how is its token renewed?

## CKP database bootstrap

- Keep H2 on a persistent volume or use a managed database? If managed, which type/version and JDBC/TLS requirements?
- Can the existing organization init-container or secret-sync mechanism deliver the database password before app startup? Database secret provisioning is deferred for the embedded H2 pilot; no database Secret is required by the chart. Provide the approved secretDockerImage and a sanitized pistol command (key/rule/secret mappings), bootstrap authentication, output format/path, permissions and renewal behavior before adding general init-container provisioning.
- Provide the database secret ID, password field slug, approved unattended identity and delivery/rotation contract. No password values are needed.

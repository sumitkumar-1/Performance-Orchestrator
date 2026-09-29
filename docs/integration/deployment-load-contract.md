# Deployment and load-generator contract

Updated 2026-09-29 from organization details supplied by the user. This is an implementation contract, not an enabled execution adapter. No deployment or remote request has been attempted.

The load generator and each service will use a configured Stash repository, with browser URL pattern `https://stash.domain/projects/SP/repos/{projectName}`. Keep repository slug separately configurable from service name. Fetch the selected ref afresh for each deployment preparation, resolve it to a commit and execute that snapshot; do not silently move an already reviewed deployment to a changed tag. The actual Git clone/archive endpoint remains to be confirmed.

The user confirms duration-controlled generation and manual stopping by scaling the workload to zero or uninstalling the release. The Java load generator exposes metrics for actual generated rate, distinct from the desired rate in configuration. Exact controller/completion signals, metric queries and YAML field names remain in [open questions](open-questions.md).

## Confirmed workflow

The organization already has a Helm-deployed load generator. Installing/upgrading its release starts load according to its configuration. The orchestrator should control that existing tool.

1. Select a target environment and services to prepare, with each service's repository, chart, release and namespace mapping.
2. Select a load profile YAML from a configured repository, potentially separate from the load-generator project. Pin both repositories to resolved commits.
3. Optionally edit a copy of the selected profile in a YAML editor. Keep the original unchanged; record the effective YAML and its hash with the run. Editing must not silently push changes to Git.
4. Validate the profile and service inputs, show the resolved environment/namespace/release/image and render deployment inputs before execution.
5. Deploy the selected services and confirm readiness before installing/upgrading the load generator. Deploying the load-generator chart may immediately produce traffic; rendering it must not start traffic.
6. Observe generated traffic and downstream received/processed/error signals for services such as SMTP-R and RDA, using each namespace's own credential reference.
7. Stop traffic using the agreed tool lifecycle, allow an agreed drain window and collect results. Do not assume Helm readiness means load has completed, or that uninstalling is the approved stop mechanism.

## Supplied Helm binding

The command runs from the load-generator project directory. Confirmed values:

| Input | Supplied value |
| --- | --- |
| Chart | `ckp/helm/ps-spoolers-ps-load-gen/` |
| Profile / values | `ckp/helm/ps-spoolers-ps-load-gen/values-perf3-baseline.yaml` |
| Release | `ps-spoolers-load-gen` |
| Namespace | `ps-spoolers-smtp-data-producer` |
| Image name | `ps-spoolers-ps-load-gen` |
| Image tag and global image tag | `0.0.4` |
| Global environment | `perf3` |
| Cluster subdomain value | `ckp-perf3-region.domain` |

The supplied `--namepsace` is a CLI typo; use `--namespace`. The chart key `clustersubdomina` may itself be intentional or a typo: preserve it until chart templates/values confirm the spelling. A Helm values key can be accepted without being used by a template, so command success alone does not prove the hostname was applied.

Command shape for implementation review only, not an instruction to execute:

```sh
helm upgrade --install ps-spoolers-load-gen ckp/helm/ps-spoolers-ps-load-gen/ \
  -f ckp/helm/ps-spoolers-ps-load-gen/values-perf3-baseline.yaml \
  --namespace ps-spoolers-smtp-data-producer \
  --kube-context CONFIRM_TARGET_CONTEXT \
  --set-string imageName=ps-spoolers-ps-load-gen \
  --set-string imageTag=0.0.4 \
  --set-string global.imageTag=0.0.4 \
  --set-string global.environment=perf3 \
  --set-string clustersubdomina=ckp-perf3-region.domain
```

The explicit context is an additional proposed binding to avoid reliance on a laptop's current context. Validate string types against the actual chart. Other services follow a similar command pattern but must have independent chart/value-key mappings; do not assume every chart uses the same image keys. Shared release names also require exclusive run ownership to prevent concurrent tests upgrading each other's load generator.

## Namespace monitoring

Select connection and credentials by explicit `(environment, namespace, signal type)` mapping. The user confirms different environments have different Loki URLs and every namespace has a separate secret. Do not reuse one namespace's credentials for another. Confirm whether metrics and logs share the same secret within a namespace.

For each generated/received/processed/failed signal record: source, query, namespace, credential reference, units, event meaning, label filters, sample interval and data freshness. Missing/denied/stale data must remain unavailable, not zero or success.

The generator's configured rate is the requested rate. Actual generation must come from a counter, status/result API or a confirmed log-derived signal. Downstream counts can differ because of retries, fan-out, filtering or in-flight messages. Comparing generated and processed rates is useful, but subtracting them is not automatically a backlog measurement. Confirm event semantics and a drain interval before evaluating delivery totals. Namespace-wide metrics also include unrelated traffic unless test-specific labels or isolation are available.

## Smallest remaining handoff

Provide one sanitized package for a single pilot:

1. Load-generator chart (`Chart.yaml`, values/schema and relevant templates), baseline YAML, source repository/ref and any separate profile repository/path. Confirm `clustersubdomina` and which fields control rate, duration, message count and destinations. Explain which fields users may edit.
2. Lifecycle: Deployment/Job type, readiness signal, how to stop, whether upgrading restarts generation, completion signal, results location/schema and actual generated-message signal. Include one manual stop/status example.
3. One downstream service's equivalent Helm command/chart, namespace/release, readiness check and received/processed/error query definitions.
4. Metrics datasource type and direct API base or Grafana URL + datasource UID, plus namespace-to-secret references/slugs, headers and one sanitized query response. Provide dev/perf environment mappings and the approved CKP execution identity/context.

No password/token values are needed in this handoff. These contracts are required before claiming real E2E execution is supported.

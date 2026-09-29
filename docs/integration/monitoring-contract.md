# Shared Loki monitoring configuration

Updated 2026-09-29: the organization confirmed **Loki / LogQL** for both logs and log-derived metrics. The configuration described here is implemented; the live telemetry polling/execution adapter is still pending. No organization endpoints have been contacted.

## Configuration ownership

- `connections.loki` defines named, shared Loki API endpoints once.
- Each environment's `monitoring.connectionRef` selects a Loki connection. Sandbox/dev/qa can reference `lower`; perf/perf3/stable can reference `higher`. The grouping and URLs are configurable examples, not network discovery.
- Each service's `deploymentDefaults.namespace` defines its namespace, with `deploymentByEnvironment` only for exceptions. Monitoring uses the same selected deployment namespace; environments do not duplicate it.
- Each service's `monitoringCredentials` maps environment IDs to credential references. One reference authenticates both log and LogQL metric queries for that service/environment.
- Credential references identify a Delinea secret and its username/password or token field slugs. Secret values are never written into configuration.

Example within `application.yaml` (Helm equivalents: `connections` and `catalog`):

```yaml
orchestrator:
  connection-defaults:
    loki:
      lower:
        apiBaseUrl: https://logs.dev-domain/loki/api/v1
      higher:
        apiBaseUrl: https://logs.perf-domain/loki/api/v1
  catalog-defaults:
    environments:
      sandbox:
        monitoring:
          connectionRef: lower
      perf:
        monitoring:
          connectionRef: higher
    services:
      smtp-receiver:
        deploymentDefaults:
          namespace: ps-spoolers-smtp-receiver
          releaseName: ps-spoolers-smtp-receiver
          valuesFiles: [ckp/helm/ps-spoolers-smtp-receiver/values.yaml]
        monitoringCredentials:
          sandbox: sandbox-receiver
          perf: perf-receiver
```

This is a partial example: service project metadata, credential definitions and environment cluster identity are configured alongside these fields.

## Standard APIs

Both log queries and LogQL metric range queries use `GET {apiBaseUrl}/query_range` with `query`, `start`, `end`, and appropriate `step`/`limit`. Instant evaluation at `GET {apiBaseUrl}/query` supports metric queries; use a range query for log streams. See the official [Loki HTTP API](https://grafana.com/docs/loki/latest/reference/loki-http-api/) and [LogQL metric queries](https://grafana.com/docs/loki/latest/query/metric_queries/).

A Grafana dashboard URL is not automatically a Loki API URL. If an organization gateway adds tenant headers or proxies requests, its contract still needs confirmation.

Example individual-message query:

```logql
{cluster_env="{{clusterEnv}}", namespace="{{namespace}}"} |= "{{mtid}}"
```

Example log-event rate (replace the event filter with the actual log contract):

```logql
sum(rate({cluster_env="{{clusterEnv}}", namespace="{{namespace}}"} |= "processed" [1m]))
```

Example count of matching events over five minutes:

```logql
sum(count_over_time({cluster_env="{{clusterEnv}}", namespace="{{namespace}}"} |= "processed" [5m]))
```

These measure matching log entries. They equal processed-message rates/counts only if each relevant message produces exactly one matching event and ingestion is complete. Namespace-wide queries can include traffic outside the current run. The earlier `message_total{...}` expression is PromQL, not an executable LogQL example; do not send it unchanged to Loki. A future Prometheus datasource, if needed, would require its own integration.

## Remaining organization inputs

- One actual generator-rate and one downstream processing-rate LogQL query with sanitized responses; identify success/failure/retry events and deduplication rules.
- Approved lower/higher URLs and environment-to-connection mapping, namespace secret IDs/field slugs, optional tenant headers.
- Cluster label, optional run/correlation labels, query time window and step, ingestion delay, partial/stale result handling and pass/fail thresholds.
- Credential lifetime for long-running background tests. Current browser sessions do not authorize unattended jobs after they expire.

## Legacy configuration

Old inline log URLs are migrated to shared Loki entries on load/import. Matching namespace credential assignments move to service/environment references using the service's deployment namespace. Identical URLs are deduplicated. Unmatched namespaces and distinct old metrics endpoints/credentials are preserved in Advanced JSON rather than silently discarded; review them before removal. They are not used as an implicit Loki metric datasource. New defaults contain no separate metrics URL or metrics credential map.

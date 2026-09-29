# Monitoring and secret provisioning contract

Updated 2026-09-17 from user-supplied examples. This document records the intended monitoring configuration, not an executable telemetry adapter. No organization endpoints have been contacted.

## Secret provisioning

Secrets are normally fetched by CKP init containers. This application should also resolve configured secret IDs on demand, with separate mappings for destinations and namespaces.

Two intended identity models:

1. Portal sign-in: the user authenticates to Delinea through the portal; its server-side session token retrieves the configured secrets.
2. Later shared/tenant identity: a provisioned token, potentially delivered by a CKP init container. Tenant-to-connection authorization must be defined before shared hosting. A configured global token must never silently replace a missing user session token.

The local app now supports portal token exchange plus explicit environment/file token delivery. It does not deploy init containers or implement tenant authorization. An init container runs at startup; ongoing token renewal needs a separately agreed mechanism. Long-running/background performance runs will need an explicit credential-lifetime model beyond a browser session.

Namespace credentials can use the common `credentials` map in `connections.yaml`:

```yaml
credentials:
  logs-service-a:
    provider: delinea
    secretServerRef: organization
    secretId: '12345'
    usernameFieldSlug: username
    passwordFieldSlug: password
  logs-service-b:
    provider: delinea
    secretServerRef: organization
    secretId: '12346'
    usernameFieldSlug: username
    passwordFieldSlug: password
```

IDs/slugs above are examples, not validated values. Namespace names select credential references; they are not themselves secret values. Each reference selects its own Delinea connection and secret ID.

## Logs

The supplied reference configuration selects a default config, a Loki base URL, cluster/environment, polling interval (5000 ms), maximum wait (60000 ms), query template, and namespace-to-secret-ID mappings. Keep the default config explicit and map target environments to named configurations rather than substituting arbitrary user input into hostnames.

User-supplied per-message LogQL:

```logql
{cluster_env="{{clusterEnv}}", namespace="{{namespace}}"} |= "{{mtid}}"
```

This is for individual-message troubleshooting. `mtid` is not a required performance-run identifier. Do not send this template with an empty identifier to pretend to scope a run. A namespace-wide log view needs an explicit approved template and bounded query window. Log credentials may differ by namespace as in the reference application.

The supplied JSON's `secretServerTokenUrl: ""` is a placeholder, not a portal token endpoint. Configure the previously supplied `/SecretServer/oauth2/token` URL for portal mode. Normalize Markdown-wrapped URLs to plain URL strings. The executable app config uses a central `secretServerRef` and `apiBaseUrl` ending in `/SecretServer/api/v1`; it appends `/secrets/{secretId}`. The reference application's full secret-URL template is not an interchangeable config field.

The supplied `maxWaitTimeMs` describes a bounded message lookup; performance monitoring needs its polling duration tied to the run and cancellation lifecycle rather than stopping after an assumed 60 seconds.

## Performance metrics

Users normally select a dashboard datasource and namespace. Supplied PromQL:

```promql
sum by (outcome) (
  rate(message_total{namespace="ps-spoolers-smtp-data-producer"}[1m])
)
```

Assuming `message_total` is a counter, this reports a per-second message rate over a one-minute lookback, grouped by outcome. It is not a total message count. `rate` before aggregation preserves counter-reset handling. See [Prometheus rate documentation](https://prometheus.io/docs/prometheus/latest/querying/functions/#rate).

Metrics and Loki logs need separate connection/query definitions. Do not send this metric query to the supplied Loki log endpoint. Namespace-wide results represent all matching traffic during that interval, including other traffic. Show this scope in the UI and reports; do not label it as traffic generated solely by a particular test.

Still needed to implement the metrics adapter:

- Grafana base URL and selected datasource UID/type, or the approved direct metrics API URL. Confirm whether requests should go through Grafana or directly to that datasource.
- Credential reference and any organization/tenant headers for that route. Do not assume Loki's namespace credentials also authorize metrics.
- Environment → datasource → namespace mapping. If a datasource spans clusters with overlapping namespace names, identify the cluster label/filter or a datasource that isolates the target cluster.
- A sanitized query request/response from the selected panel, including query interval/step. The one-minute rate window needs sufficient scrape samples; confirm the scrape interval and ingestion delay.
- Meaning of each `outcome` value, including which count as success/failure; whether signals are display-only or feed pass/fail thresholds.

LogQL template escaping, query limits, partial results, and unavailable/stale telemetry will be handled explicitly when implementing the live telemetry adapter.

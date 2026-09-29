# Local operation

Run `mvn verify`, then `./scripts/run-local.sh` from the checkout. Select JDK 21 or newer first. Configuration paths are relative to the repository root unless supplied as absolute paths. `--server.port=8081` can select a different local port. Non-loopback addresses fail startup. Choose `--mode simulation` for the mock workflow or `--mode real` for live read-only connections. Real execution adapters remain unavailable. Real mode stores its state separately under `data/real/` by default.

The launch script applies umask 077. Data, profiles, plans, events, audit records, simulated deployments/loads and reservations live in the H2 database under `data/`. H2's file locking prevents two processes from opening the same database. Separate local databases do not coordinate with one another and must never be used as real shared-environment coordination.

## Backup and restore

1. Stop the local Java process cleanly (Ctrl-C in its terminal).
2. Back up the entire `data/` directory and the exact catalog/configuration files together to approved private storage. Credential references may be retained; secret values must remain in Delinea or the approved provider.
3. Restore the directory while the application is stopped, with permissions restricted to the developer. Restore the matching fixture catalog and run the same application version before upgrading migrations.
4. Start the app. A committed active simulated stage resumes; completed history and profiles remain available. Do not delete a reservation just because its expiry time has passed.

This release has no automated retention/purge job. History remains until an administrator archives or replaces the stopped local database. Artifact files can be regenerated from retained database records. Do not delete active run data; take a full backup before resetting a disposable workspace. Data volume limits and retention policy must be set before shared use.

## Failure recovery

A deployment or readiness fixture failure routes through cleanup and never starts load. Cancellation is first acknowledged as CANCEL_REQUESTED, then stops the owned simulated load and finishes CANCELLED. Healthy services are kept. Missing metrics are unavailable rather than zero.

The cleanup-failure fixture finishes NEEDS_ATTENTION and retains the environment reservation. Open the run and choose **Verify cleanup & release**. This simulation-only recovery checks that the load is stopped and records an audit event before releasing the reservation. Real uncertain outcomes must never use this simulated recovery path.

## Troubleshooting

- `release version 21 not supported`: select a JDK 21–25 in `JAVA_HOME` and `PATH`.
- Maven download errors: allow access to Maven Central or configure your organization's approved Maven mirror.
- Database already open: stop the other process using that exact data directory.
- Port already in use: stop the previous local app or choose another port.
- HTTP 403: use the same loopback host in browser and API; obtain a fresh `/api/v1/session` token and retain its session cookie for mutations. Cross-site requests are rejected.
- Plan conflict: refresh the profile or plan; the environment may be reserved, catalog changed, or baseline drifted.
- SOURCE_DENIED: verify referenced Delinea/JFrog permissions and externally supplied tokens. Remote response bodies are deliberately absent from application errors.
- SECRET_SCHEMA: confirm Delinea API version, secret template and configured field slugs.

Public networking, ingress, SSO, PostgreSQL backup/restore, shared artifact retention, telemetry queries, Kubernetes RBAC and real deployment operations remain organization-specific prerequisites; none are implied by successful local simulation.

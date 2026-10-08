# Local operation

Use Java 21+ and `./scripts/run-local.sh build-run`. The application has one configured-integration workflow; no mode flag is needed. See the [README](../../README.md) for startup and execution settings.

The launch script applies umask 077. The embedded H2 database, artifacts, overrides and preparation workspaces retain their existing `data/real/` locations. H2 locking prevents two processes opening the same database. Separate databases do not coordinate shared cluster reservations.

Before upgrading, finish active runs, stop the process, and back up persistent state. Restart with the same paths. Saved profiles and history remain; prepare new reviews after an upgrade. Do not release a reservation solely because a lease timestamp has expired.

Cancellation stops the run's owned baseline and additional load releases; service releases remain. Failed cleanup retains the environment reservation and reports NEEDS_ATTENTION. Use **Verify cleanup & release** to retry cleanup with the correct cluster permissions.

For local cluster access, inherit PATH, KUBECONFIG and the existing login environment. On CKP configure a service account and RBAC, with in-cluster execution targets. Authentication to Secret Server alone does not grant Kubernetes permission. Use connection diagnostics and the optional per-run activity console to inspect failures without exposing credentials.

The pilot remains loopback-only. Use port-forwarding on CKP. Organization network access, trusted CAs, shared-user authorization, backups and RBAC require organization-approved setup.

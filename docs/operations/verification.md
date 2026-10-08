# Verification

Run `./scripts/run-local.sh build` with Java 21+ and Maven. This performs a clean build, tests, and packaging. Run `npm run format:check` for formatting and lint/render the CKP chart with `scripts/deploy-ckp.sh render` before deploying.

Tests cover the production workflow: authentication/session isolation, credential references, registry and Bitbucket contracts, chart preparation and dependency resolution, environment selection, ordered deployment, owned-load cleanup, additional loads, cancellation/recovery, monitoring and configuration persistence. Test doubles replace external transports and commands; no test mode is packaged in the application.

Use the office runbook for live verification. Passing local tests does not prove office TLS, network access, RBAC, chart compatibility or approved LogQL queries. Perform the first end-to-end run in sandbox and inspect the run diagnostics and cleanup outcome.

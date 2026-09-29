# Verification record

Verified locally on 2026-09-16 UTC with Temurin JDK 24, Maven 3.9.12, and the Codex in-app Chromium browser. Java compilation targets release 21. No organizational server or CKP cluster was contacted.

## Automated verification

`mvn verify` passes 31 tests across five suites:

- Workflow integration: 16 cases, including all six simulation outcomes, preserved mixed-source plans, unchanged project inputs, optimistic profile revisions, concurrent environment acquisition, idempotency, cancellation before/after load, baseline drift, HTTP CSRF/Host/Origin validation, unknown JSON fields and persisted event cursors.
- YAML semantics: 3 tests cover map/list/scalar merge, explicit deletion, null rejection, duplicate keys, unsafe tags, collection/scalar aliases, size bounds and managed-field deletion protection.
- Preparation: 6 tests cover missing/extra chart placeholders, path traversal, dependency cycles, absent installation mappings, namespace allowlists and real/shared-mode startup refusal.
- Registry/Secret Server contracts: 5 tests cover mapped Delinea fields, redacted secret representations/errors, mapped JFrog pagination, manifest digest verification, denied source access, username traversal, unknown services and HTTPS restrictions.
- Restart: 1 test creates a file-backed database, starts a simulated load, closes all connections, reconstructs application services against the reopened database, verifies the same operation identity and one load record, then cancels and releases the reservation.

The registry/secret transport is mocked in tests. Live endpoint authentication, TLS/private CAs, Delinea token acquisition and deployed response schemas remain unverified until approved configuration is available.

## Browser verification

- Opened the seeded dashboard, launched **Run now**, watched all stages reach SUCCEEDED / PASS, and opened the HTML report.
- Opened the saved profile, changed only `auth-service` from dev to QA and selected another version. Verified `smtp-receiver` stayed on release/build-102.
- Saved the edited profile and reloaded; verified QA/build-101 persisted independently. Restored dev/build-103 and saved again.
- Previewed an immutable two-service plan with distinct namespaces and source selectors. Executed the preview and verified SUCCEEDED / PASS with 500 synthetic requests, 100 requests/second, error ratio 0.001 and p95 120 ms.
- Verified history survived a backend restart. Fixed a dashboard field-access bug discovered when the first run appeared.
- Inspected dashboard and report screenshots. At a 390px viewport, the run page had document width and scroll width of 390px (no horizontal document overflow).
- Reports expose summary, UTC timing, selected versions, thresholds, cleanup and timeline, with detailed pinned evidence behind a disclosure.

These checks establish local simulated behavior. They do not demonstrate real Helm deployment, image pull permission, readiness, load-gen control, production authorization or cluster recovery.

## Deployment-dialog UI follow-up

Verified after replacing the all-service checklist with a catalog-backed Add deployment dialog:

- A new profile starts with an empty deployment list. The dialog lists configured services; added services are excluded to prevent duplicates, and removal returns them to the picker.
- Added auth-service/dev/build-103 and smtp-receiver/release/build-102 independently. Editing auth and cancelling preserved the original selection.
- Selected the user source, supplied an artifact-owner username and chose a discovered build. Changing the environment updated namespace/release previews while preserving both service image choices.
- Escape and Cancel closed the native modal; no draft selection was added. The modal uses native focus trapping and restores focus on close.
- Saved a profile through the new editor, reopened it from the dashboard, and generated a valid immutable plan with the expected selected service and perf3 destination.
- At a 1360px browser viewport, history's two selects, text input and Filter button each measured 42px high with identical bottom coordinates. Inspected the dialog and history screenshots.
- At a 390px viewport, page width/scrollWidth were both 390px; dialog clientWidth/scrollWidth were both 350px. Cancellation remained reachable in the scrollable modal.
- JavaScript module syntax checks and Maven packaging passed. No backend contracts or database schema changed; the prior backend test results remain separate from these browser checks.

These browser checks used Chromium. Native Safari rendering was not directly automated; native select appearance and explicit control heights were normalized to address the supplied Safari screenshots.

## Secret Server authentication update — 2026-09-17

`mvn -o -B verify` passed on Temurin 24 targeting Java 21: **39 tests, zero failures/errors**. Eight added tests cover form encoding/password whitespace, token redaction, browser-session isolation, expiry, failed reauthentication, namespace secret retrieval, rejected token eviction, mounted-file rotation/size limits, explicit modes, CSRF/origin checks, session ID rotation, and loading the example configuration. Existing simulation and registry tests remain passing. External HTTP calls are mocked; these results do not validate live Delinea/Artifactory contracts.

`node --check src/main/resources/static/js/app.js` passed. A separate loopback preview on port 8081 with an in-memory database and example configuration confirmed that portal username/password controls render correctly, mounted-token status is distinct, and registry discovery without a portal token displays a sign-in requirement. No credentials were entered and no organization endpoints were contacted. The temporary preview was stopped after verification.

Live telemetry, tenant authorization, token refresh and background-run credential delegation remain unimplemented. See the [monitoring contract](../integration/monitoring-contract.md).

## Resources and runtime configuration — 2026-09-17

`mvn -o -B verify` passed: **44 tests, zero failures/errors**. Added coverage verifies persisted configuration restoration, stale-editor conflicts, invalid catalog/connection rejection without changing memory or disk, filesystem write failure, classpath project reads, portal token invalidation, CSRF protection, stale plan submission rejection and accepted runs finishing against their original snapshot. Existing traversal/cycle/namespace tests now assert rejection during catalog loading, before planning.

The packaged JAR started from `/tmp` on isolated loopback port 8081 with an in-memory database, proving it no longer depends on a checkout's fixtures directory. Browser verification saved an environment label through the runtime editor and observed it immediately in the environment catalog. The preview used a temporary configuration file and was stopped afterward. `node --check` passed for the updated UI module.

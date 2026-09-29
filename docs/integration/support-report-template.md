# Office integration report

Copy this template for each build/pilot. Keep the complete report internally; share only an organization-approved sanitized copy. Do not include passwords, tokens, cookies, Authorization headers, raw HAR files, secret contents or unreviewed runtime configuration/log bundles.

## Build and runtime

- Date/time and timezone:
- JAR SHA-256 (include in every report):
- OS/architecture:
- `java -version` (and `mvn -version` if building):
- Working directory (sanitized):
- Launch arguments (paths/hostnames sanitized; no credentials):
- Mode shown in UI:
- Port / laptop or CKP port-forward:
- VPN/proxy/private CA setup confirmed by IT:
- First startup or existing persisted configuration:
- Configuration changed through UI or bootstrap file:

## Checkpoint results

| Checkpoint | Pass / fail / not attempted | Time and notes |
| --- | --- | --- |
| Build and launch | | |
| Real mode and source visible | | |
| Delinea sign-in | | |
| Secret retrieval / tag discovery | | |
| Known tag found | | |
| Digest resolved | | |
| Runtime change survives restart | | |

## One reproducible failure

- First failed checkpoint:
- Minimal numbered reproduction steps:
- Expected result:
- Actual result:
- Exact application error code, field and message:
- Sanitized startup exception if startup failed:
- Did this work in the previous build? Previous checksum:
- Last successful checkpoint and changes since then:
- Does the owner have a working equivalent request? Same identity/network?:
- Sanitized destination alias and full route shape (preserve `/v2/`, repository/image boundaries):
- Response HTTP status and field names/types, with all secret values removed:
- Remote owner findings for the matching timestamp, if available:

For a token response, provide field names/types and whether expiry is positive, never the token. For a secret response, provide only structure and relevant slug names, never `itemValue`. For registry issues, include a sanitized source mapping and one example tag/digest only if approved. Screenshots should exclude passwords, internal identifiers and unrelated data.

## Next adapter contract

- One representative service and target dev environment (aliases):
- Approved source retrieval/deployment/load/query examples attached (sanitized):
- Expected success and failure response shapes:
- Timeouts, workload ceilings, stop and cleanup expectations:
- Owner-confirmed differences from the runbook:
- Questions requiring a code change:

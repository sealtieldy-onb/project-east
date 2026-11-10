# Backend QA audit

Date: 2026-11-10
Target: local `main` at `5308c4708172417786f6ee6dcd98eec51611f7c2`

## Result

No reproducible backend defect was found in this audit. The offline Maven suite passed: 91 tests run, 0 failures, 0 errors, and 2 opt-in PostgreSQL acceptances skipped. The skipped tests are `PersistentSessionPostgresAcceptanceTest` and `RetentionSettingsPostgresAcceptanceTest`; this audit did not connect to the configured database. Separate isolated PostgreSQL acceptance passes are recorded in `session-cookie-recovery-qa.md` and `project-progress.md`, but were not repeated here.

Command: `mvn -o -B -f backend/pom.xml test`.

## Coverage exercised

- **Session and cookie recovery:** three malformed/legacy/valid-cookie filter tests and two session-attribute encryption tests passed. Existing isolated acceptance evidence covers restoring an app-issued session across two Spring contexts, malformed-cookie expiry, cache-only search, and explicit logout.
- **Cache and query boundaries:** local metadata search and on-demand Gmail repository behavior tests passed. Retention setting/expiry acceptance is opt-in and was skipped by this invocation.
- **Email HTML and images:** the HTML sanitizer, Content-ID mapping, inline-image policy, and read-service image tests passed. No external image URL was requested.
- **Authorization and mutations:** mail-action ownership, single/bulk action routing, attachment authorization, and mail-read ownership tests passed. These are synthetic service tests; no Gmail mutation was made.
- **Drafts and attachments:** encryption, send guards, Gmail reconciliation, recipient/source composition, attachment ownership/cache behavior, and MIME review-snapshot tests passed. No draft was sent or changed in Gmail.
- **Reports and AI:** report/draft-assist acceptance, structured-output validation, provider-client behavior, and credential-encryption tests passed using fakes or synthetic inputs. No external model provider was contacted.

## Findings

| Severity | Status | Finding |
| --- | --- | --- |
| — | No confirmed defect | The exercised backend tests completed without failures or errors. |

## Remaining verification limits

- Real Gmail OAuth, mailbox refresh/revocation, multi-account search, and remote API failure behavior were not exercised. The user authorized read-only Gmail for broader UI QA; this backend-only audit deliberately used fixtures and made no Gmail calls.
- Gmail mutations and send were not exercised against a live account. Existing synthetic tests verify service routing and confirmation/reconciliation boundaries, but cannot prove the live provider accepted a request.
- No external AI-provider request was made; provider behavior was tested through fakes.
- This Maven invocation skipped the two opt-in PostgreSQL acceptances. Their separately recorded isolated-database results are not a rerun by this auditor.
- No application code was changed. No configured East database, real email content, credentials, Gmail, or AI provider was accessed.

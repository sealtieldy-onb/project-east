# Phase 3 report backend workstream

Date: 2026-11-10
Status: implementation and synthetic manual-report, daily-scheduler, and AI-draft acceptance checks are complete. Three independent read-only review findings have been fixed. After user authorization, Liquibase changeset 009 was applied to the configured local East database and verified with a disposable PostgreSQL acceptance run.

## Scope

Implement authenticated Dashboard report reads and explicit report generation; enforce provider disclosure, mailbox ownership, the 30-message expansion gate, text-only inputs, structured source validation, encrypted report/insight persistence, and retention/disconnect behavior. Add opt-in timezone-aware daily reports and idempotent most-recent-day catch-up. Add explicit AI-assisted new/reply drafts that create only an encrypted local draft.

## Recovery notes

- Follow `phase3-report-api-contract.md`; no Dashboard read may invoke the model.
- Use synthetic fixtures only. No live Gmail or provider requests are authorized.
- Provider configuration uses the existing encrypted `ai_provider_configuration` row. Never persist keys, prompts, raw body text, provider responses, or provider errors in report rows.
- Persist every message body included in a report prompt in `summary_run_source`; insight-level sources must be a subset of those rows.
- Migration 009 and its master registration are complete. Preserve changesets 001–008 and do not modify 009 without coordinating its owner.
- The initial allowance is 30 eligible messages per selected mailbox; additional counts aggregate excess messages across scope. One manual expansion reads at most 240 additional bodies, with the cap included in the approval response and UI.
- Manual reports, daily-schedule enablement, and AI-assisted drafts carry a non-secret SHA-256 fingerprint of provider type, endpoint, and model. Compare it before loading content for provider processing and recheck immediately before transmission.
- Keep insight text, urgency, deadline, and transaction data inside encrypted insight content. The writer leaves legacy plaintext urgency/deadline columns null.
- The frontend Dashboard and composer workstreams have handed off. See `phase3-dashboard-ui.md`; avoid overlapping UI edits without reviewing that scratchpad.
- The checkout remains on `feature/phase-2-acceptance` because `.git` did not allow creating a Phase 3 branch. Preserve existing Phase 2 work.
- The report and scheduler services use the narrow `AIProviderGateway` boundary; draft assistance depends on `DraftAssistantStore` and `DraftAssistantSourceLoader`. Production wiring still resolves to the existing provider, encrypted draft service, and cached-source service. Synthetic service tests inject local fakes through these boundaries.

## Implemented backend paths

- `GET /api/v1/dashboard` performs an ownership-scoped database read only. It does not invoke a provider or Gmail.
- `POST /api/v1/dashboard/summaries` requires explicit provider/content confirmation and the provider fingerprint. It lists metadata first, returns `EXPANSION_REQUIRED` before body fetch when any selected mailbox exceeds 30, and reads only after `ALLOW` or `DECLINE` is submitted.
- Model inputs include bounded plain text plus sender/subject/time/account metadata. Attachment parts are excluded. Source tokens must map to prompt messages; every included input receives run provenance.
- Narrative and insight content use the local vault with user/run/insight AAD. Scope is recorded even when an account contributes no body. Expiry follows the earliest source-body expiry; source-less reports follow the configured TTL. Disconnect cleanup removes reports that used the disconnected account.
- Daily reports are opt-in, timezone-aware, and use unexpired local bodies without Gmail reads. Each run covers the previous local calendar day and uses a globally capped set of at most 240 cached bodies with fair per-mailbox ordering. The scheduler checks the disclosed provider fingerprint and claims one most-recent report date to prevent duplicate generation/catch-up.
- `POST /api/v1/drafts/assist` requires explicit disclosure/version confirmation, validates mailbox/source/recipients before provider processing, excludes attachments, and writes an encrypted app-local draft. Gmail save and send remain separate actions.

## Checks

- `ReportPhase3AcceptanceTest` passed for Dashboard reads that do not invoke provider completion or Gmail, daily schedule opt-in and provider-change disclosure, header-count preview and expansion gates, the 30-message allowance plus 240 additional-body cap, exclusion of attachment sentinel data from prompts, encrypted narrative and insight persistence, source provenance and expiry, previous-local-day catch-up across the November 2026 DST boundary, global daily cap of 240 cached bodies, and duplicate-date suppression.
- `DraftAssistPhase3AcceptanceTest` and `DraftSourceCompositionTest` passed for required disclosure, provider-configuration mismatch stopping before source loading, attachment-content exclusion from reply prompts, and an actual encrypted local assistant draft with no Gmail mutation.
- `mvn -q -Dtest=ReportPhase3AcceptanceTest,DraftAssistPhase3AcceptanceTest,DraftSourceCompositionTest test` passed.
- Full `mvn -q test` passed 71 tests with 1 opt-in PostgreSQL acceptance test skipped and no failures or errors.
- The focused tests use a fake provider gateway, synthetic encrypted rows, and in-memory repositories. They do not verify PostgreSQL migration execution or database-backed retention/disconnect cleanup.
- `mvn -q -DskipTests test-compile` passed after adding the test seams. The prior `npm run build` passed (`tsc -b`, Vite; 2,080 modules transformed); no frontend files changed during this acceptance work.
- On 2026-11-10, the opt-in PostgreSQL acceptance run applied all nine Liquibase changesets to a disposable database and passed the synthetic cache-expiry, derived-report retention, combined-report disconnect, and empty-mailbox-scope cleanup checks. The disposable database was removed afterward.
- Changeset 009 was applied successfully to the configured East database. Its `DATABASECHANGELOG` row is `EXECUTED`; aggregate checks found no unscoped report sources or insight sources without report provenance. The configured database currently has no report, report-source, insight-source, or report-scope rows.
- The first configured-database startup attempt ran Liquibase 009 successfully, then its second application context stopped at `EastSingleInstanceGuard` because an East process was already running. That earlier process was left untouched at the time; the later user-requested fresh runtime is recorded below.
- The PostgreSQL acceptance test and `AIProviderClientTest` passed; the latter confirmed the explicit Spring production-constructor wiring fix. The final full `mvn -o -B -f backend/pom.xml test` passed 71 tests with the opt-in PostgreSQL test skipped, and `git diff --check` passed.
- No Gmail or provider request was made during database verification. Scheduler delays were extended during the PostgreSQL acceptance run and database startup attempt.

## Remaining work

- Changeset 009 and database-backed retention/disconnect checks are complete. No further migration work is pending.
- **Fresh runtime on 2026-11-10:** At the user's request, restarted the backend from current source on 127.0.0.1:8081 and Vite on 127.0.0.1:5173, explicitly targeting the backend at 127.0.0.1:8081. Liquibase found all nine changesets current. Read-only GETs through Vite returned 200 for `/` and 401 for `/api/v1/dashboard` without an active session, showing the backend responded through the proxy; authentication prevented exercising the controller path. The restart cleared the backend's in-memory sign-in session. Gmail sync and daily-report initial and recurring delays are 31,536,000,000 ms in this process to prevent automatic background work; no Gmail/provider request, sign-in, report generation, or tests were run during the restart.
- Keep live Gmail checks deferred; do not request sign-in.

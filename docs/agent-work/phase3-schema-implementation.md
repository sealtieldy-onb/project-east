# Phase 3 report schema implementation

Date: 2026-11-09
Status: changeset 009 is registered and was applied to the configured local East database on 2026-11-10. Disposable PostgreSQL migration and synthetic retention/disconnect acceptance also passed.

## Changes

- Added `009-phase3-report-scope-and-scheduling.sql` after migrations 001–008. Earlier migrations remain unchanged.
- Added explicit report purpose, coverage, eligible/selected/additional counts, expansion approval, provider/model/config-version, prompt/ruleset version, and scheduled-report date metadata to `summary_run`.
- Added a unique owner/date index for `DAILY` and `CATCH_UP` runs. This gives the scheduler a durable idempotency key even after a failed or interrupted attempt; an implementation can resume/update the existing row rather than creating another run for the day.
- Added provider disclosure version and last-attempt/last-success date and timestamp markers to `summary_schedule`.
- Added `summary_run_mailbox` so a caller can persist every selected mailbox, including a mailbox that contributed no eligible message. Composite foreign keys bind report, user, and mailbox ownership.
- Added `AWAITING_REPLY` and `OPEN`/`DISMISSED`/`HANDLED` insight state support. Insight-source rows now include the owning report, user, and mailbox and must reference a source message recorded for that report.
- Backfilled legacy insight source messages into `summary_run_source` before enforcing the new provenance relationship. Existing summary scope is reconstructed from known message provenance; empty mailbox scopes from reports created before this migration cannot be recovered. Those rows retain `LEGACY` purpose and `UNKNOWN` coverage.
- No email content, prompt text, API response, endpoint URL, credential, or API key is persisted by this migration. Provider configuration versions should be non-secret identifiers or hashes.

## Caller requirements

- Insert `summary_run_mailbox` rows before corresponding `summary_run_source` and `assistant_insight_source` rows, in the same transaction as the report and insight writes.
- For new reports, explicitly populate the metadata columns rather than relying on the compatibility defaults (`LEGACY`, `UNKNOWN`, and zero counts).
- For scheduled runs, use the user's local report date in `scheduled_report_date`; reuse an existing run with that owner/date key when recovering an interrupted attempt.
- On provider/model/endpoint changes, pause daily generation until the user confirms the new disclosure and the schedule's `provider_disclosure_version` matches the current non-secret provider configuration version.
- Mailbox disconnect must delete every report whose `summary_run_mailbox` includes that mailbox, including combined reports whose other sources remain connected.

## Checks and limits

- Manually checked the changeset against migrations 001, 007, and 008 and the Phase 3 report API contract.
- Liquibase applied changesets 001–009 successfully to a fresh disposable PostgreSQL database and applied changeset 009 to the configured East database. The configured `DATABASECHANGELOG` records 009 as `EXECUTED`.
- The opt-in PostgreSQL acceptance test passed against a disposable database, verifying expiry updates, source-derived report/insight cleanup, preservation of message metadata, mailbox disconnect cleanup for combined reports, and deletion of a report scoped to a mailbox with no source messages. The disposable database was removed.
- Aggregate provenance checks against the configured database found no unscoped report sources or insight sources without report provenance; no Phase 3 report or source rows were present after application.
- The configured-database `spring-boot:run` invocation applied 009, then stopped at the single-instance guard because another East process was already running. That existing process and UI session were left running.
- Existing report rows with no source provenance cannot be assigned a mailbox scope by inference; the migration leaves them unscoped and marks their purpose/coverage as legacy/unknown.

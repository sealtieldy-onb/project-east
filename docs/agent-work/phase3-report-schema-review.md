# Phase 3 report schema review

Date: 2026-11-09
Scope: read-only review of migrations 001 and 007–008, the report retention and mailbox-disconnect queries, reset behavior, and the Phase 3 technical/product requirements. No database, Gmail, provider, credential, test, or application-service changes were made.

## Current schema

- `summary_schedule` (migration 001): `id`, unique `app_user_id`, `enabled`, `run_at_local`, `timezone_id`, and `updated_at`. It has no report-window field or last-attempt/success/catch-up marker.
- `summary_run` (migration 001): `id`, `app_user_id`, `period_start`, `period_end`, `run_status` (`PENDING`, `COMPLETED`, `FAILED`), encrypted narrative nonce/ciphertext, `expires_at`, and `created_at`. It validates the date window and encrypted payload pair, and has an expiry index.
- `summary_run_source` (migration 007): `summary_run_id`, `app_user_id`, `mailbox_id`, `message_id`, and `created_at`. Composite foreign keys bind the run to its owner, mailbox to its owner, and message to its mailbox. It has a run/message primary key plus mailbox and message lookup indexes.
- `assistant_insight` (migration 001): `id`, `summary_run_id`, `insight_kind` (`URGENT_ACTION`, `REPLY_RECEIVED`, `TRANSACTION`), `confidence`, optional `urgency` and `due_at`, encrypted content nonce/ciphertext, `expires_at`, and `created_at`.
- `assistant_insight_source` (migration 001): `insight_id`, `message_id`, with cascading foreign keys and an `(insight_id, message_id)` primary key. It does not store owner or mailbox columns, nor does it use a composite message/mailbox foreign key.
- Migration 008 adds mailbox sync state and does not alter report tables. The changelog currently ends at 008.

## Existing cleanup and ownership behavior

- `SettingsService.updateBodyCacheDays` recalculates `assistant_insight.expires_at` from linked source bodies and `summary_run.expires_at` from `summary_run_source` plus insight-level sources. A report writer must therefore persist every message body used by the narrative, even when that message is not cited by a particular insight.
- `RetentionCleanupRepository` deletes runs when their own expiry or a linked run/insight source body expires. It separately deletes insights by insight expiry or source-body expiry. Missing provenance links would bypass these safeguards.
- `MailboxService.disconnect` calls `MailboxRepository.deleteSummaryRunsForMailbox` before deleting the mailbox. That query removes runs with a `summary_run_source` row for the mailbox or an insight source message in that mailbox. This protects combined reports only when all model inputs are linked, including narrative-only inputs.
- `LocalDataResetService` truncates `summary_run`, insights, sources, and schedules with `CASCADE`, so the report rows and dependent sources are removed during a full local reset.

## Phase 3 gaps

- Source rows describe messages actually used, but they do not record the requested mailbox scope. A report scoped to an empty mailbox, or a request whose selected mailbox yielded no eligible messages, cannot be represented unambiguously.
- `summary_run` lacks actual coverage state/counts, whether the user approved reads beyond the 30-message baseline, report purpose (first-link, manual, daily, catch-up), and provider/model or prompt/ruleset version. Its current status cannot distinguish a completed partial report from a complete report.
- `summary_schedule` has no durable last-attempt or last-successful report date. The scheduler needs a deduplication key or successful-period marker so catch-up creates at most one report for the most recent missed day.
- `assistant_insight.insight_kind` does not represent the planned `AWAITING_REPLY` candidate separately. It also has no `OPEN`/`DISMISSED`/`HANDLED` status even though the technical domain model describes one.
- Insight source rows are not explicitly bound to the run's owner/mailbox scope with composite keys. Message IDs are globally primary-keyed today, but consistent composite ownership constraints would make the provenance boundary auditable at the database layer.

## Recommended next migration/API boundary

Coordinate one new migration after 008; do not edit 001 or 007. A focused Phase 3 changeset should:

1. Add an explicit run-scope relation such as `summary_run_mailbox(summary_run_id, app_user_id, mailbox_id)` with composite owner/run and mailbox/owner foreign keys. Insert every requested mailbox even when the report finds no messages.
2. Add allowlisted run metadata for `run_purpose`, coverage (`COMPLETE`/`PARTIAL`), eligible/selected message counts, and safe provider/model plus prompt/ruleset identifiers. Keep failure text sanitized and do not persist prompts, message bodies, API responses, or provider error bodies.
3. Add a durable daily-report date/attempt marker or a unique key for scheduled runs, plus last-attempt/last-success date fields as needed for idempotent most-recent-day catch-up.
4. Extend the insight category/status constraints for awaiting-reply and the planned open/dismissed/handled states, and add composite owner/mailbox scope to insight sources where practical.
5. Keep `summary_run_source` as the complete set of all body inputs, while `assistant_insight_source` remains the narrower evidence set for each displayed claim. Persist both transactionally with each run so retention and mailbox-disconnect cleanup can rely on them.

The manual generation API should require an explicit user request and provider-disclosure acknowledgement. If the selected date/mailbox range exceeds the initial 30-message baseline, the server should report the additional read count first; only a separate confirmed request may fetch beyond that limit. A declined expansion should produce a `PARTIAL` report from allowed inputs. The scheduler should only invoke the same report service when daily reports are enabled and should never infer consent to expanded Gmail reads from the schedule alone.

No migration number or schema change is claimed as implemented here; migration ownership and final endpoint DTOs remain for the parent workstream to coordinate.

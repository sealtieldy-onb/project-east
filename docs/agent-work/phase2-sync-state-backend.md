# Phase 2 mailbox sync state — backend work log

## Goal

Persist a safe per-mailbox sync state and expose it to the Dashboard through `MailboxSummary.syncStatus`. The status describes local sync health, not provider diagnostics. A mailbox with no connected-mailbox row is not connected; the persisted state applies only to connected rows.

## Implementation

- Added `MailboxSyncStatus` with the API/database values `pending`, `syncing`, `ready`, `needs_reauthorization`, `partial_failure`, and `error` (`backend/src/main/java/com/east/app/auth/MailboxSyncStatus.java:8-34`). Jackson serializes the enum as its lowercase value.
- Added Liquibase changeset `008-mailbox-sync-state`; existing mailboxes with a reauthorization flag backfill to `needs_reauthorization`, previously synced rows to `ready`, and other rows to `pending`. The column is non-null, defaults to `pending`, and has a check constraint (`backend/src/main/resources/db/changelog/db.changelog-master.xml:47-52`, `backend/src/main/resources/db/changelog/008-mailbox-sync-state.sql:1-23`).
- Added status to mailbox persistence and the safe summary returned by the mailbox API (`backend/src/main/java/com/east/app/auth/MailboxRepository.java:23-27,65-104,123-181,223-241`, `backend/src/main/java/com/east/app/auth/MailboxSummary.java:6-16`, `backend/src/main/java/com/east/app/auth/MailboxService.java:112-124`). The JSON field is `syncStatus`.
- Reconnecting an account clears the prior reauthorization flag and resets state to `pending` (`MailboxRepository.java:138-150`). Starting a sync records `syncing`; a successful initial commit or history cursor advance records `ready` in the same transaction as the data/cursor update (`backend/src/main/java/com/east/app/gmail/GmailSyncRepository.java:45-85,111-153`).
- Gmail resource HTTP 401 during sync invalidates the cached access token and persists `needs_reauthorization`. An invalid refresh grant also uses the same path (`backend/src/main/java/com/east/app/gmail/GmailSyncService.java:260-302`, `backend/src/main/java/com/east/app/auth/GoogleAccessTokenService.java:63-89,112-120`). Non-401 Gmail failures, refresh-provider failures, and runtime errors are retryable `error`; they do not mark the account unauthorized.
- History sync tracks committed non-empty page chunks. A subsequent failure becomes `partial_failure` only if at least one message/deletion chunk was committed; otherwise it becomes `error` (`GmailSyncService.java:349-409,426-457,953-970`). Empty page bookkeeping is not counted as committed page data.
- No provider error body or detail was added to the stored sync status. `MailboxSyncStatusJsonTest` verifies the JSON property and lowercase value (`backend/src/test/java/com/east/app/auth/MailboxSyncStatusJsonTest.java:10-28`).

## Focused coverage

`GmailSyncStateTest` covers a successful initial sync, resource 401 reauthorization, a 429 remaining retryable, and history failures both after and before a committed page-data chunk (`backend/src/test/java/com/east/app/gmail/GmailSyncStateTest.java:24-91`). `GoogleAccessTokenServiceReauthorizationTest` verifies conditional invalidation for current versus replaced tokens, and `GmailRequestGuardTest` verifies that only a current-token 401 is marked as requiring reconnection. Mail-read and attachment handler tests verify refresh-token failures return `MAILBOX_AUTH_REQUIRED` to the UI.

## Verification

- Ran `mvn -o -B -f backend/pom.xml test` from the repository root on 2026-11-09 after token-aware 401 and draft recovery changes.
- Result: `BUILD SUCCESS`; 27 tests, 0 failures, 0 errors, 0 skipped.
- Ran `git diff --check`; it reported no whitespace errors.
- The configured PostgreSQL database and Liquibase application were not run. No live Gmail/browser interaction or East start/restart was performed, so the schema migration and a real Gmail 401 still need runtime acceptance in the user’s local app.

## Notes / boundaries

- Scheduled and manual sync retain their existing `GmailSyncService` 401 handling. `GmailRequestGuard` now applies the same behavior to mailbox-scoped read/list/search/label, action, attachment, and draft/source requests. It marks only HTTP 401 as `needs_reauthorization`, invalidates the cached bearer token, preserves the original exception when status persistence fails, and rethrows without retrying the request.
- A successful OAuth reconnect resets the visible status to `pending`; the next successful initial/history sync changes it to `ready`.
- Do not record account identities, credentials, message content, or mailbox-derived values in this work log.

## Configured database migration/runtime follow-up — 2026-11-09

- Restarted the backend on port 8081; Liquibase applied `008-mailbox-sync-state` successfully to the configured PostgreSQL database (1 changeset run, 7 previously applied, 8 total; 2 rows affected by the data backfill).
- After the restart, the user signed in again. East's read-only Dashboard displayed both connected mailboxes as **Synced**, verifying the configured-database ready-state path through the UI. No Gmail mutation or email-content inspection occurred.
- A fresh disposable database successfully applied migrations 001–008 during a Spring Boot startup on port 8083; the database and temporary key were removed afterward. Real HTTP 401 handling and failure/partial-failure presentation remain unverified.

## Cross-endpoint Gmail 401 handling — 2026-11-09

- Added `GmailRequestGuard.call/run` as a shared wrapper around a Gmail request when the caller has the owning `appUserId`, `mailboxId`, and exact access token used for that request. On HTTP 401 it conditionally marks reauthorization only if that token is still the current cached token; a late 401 from an older request leaves a replacement token and mailbox connection intact. Persistence errors are logged without provider text and do not replace Gmail's original exception. Other HTTP statuses pass through unchanged, and requests are never retried.
- Wrapped Gmail request sites in `MailReadService`, `MailActionsService`, `AttachmentContentService`, `DraftSourceService`, and `DraftService`. On-demand hydration records a 401 against the token fetched inside `GmailSyncService`, and scheduled/manual sync uses the same token-aware handling. A stale sync 401 records retryable sync failure instead of leaving the mailbox stuck in `syncing`.
- Draft save/send keeps the reconciliation path for uncertain results. A definite HTTP 401 restores `SAVE_PENDING`/`SEND_PENDING` to the prior local or Gmail-saved state; a still-current rejected token returns `MAILBOX_AUTH_REQUIRED`, while a stale-token rejection returns a retryable error. No request is retried automatically. Read/action/attachment/draft response handlers avoid requesting reconnection for a stale-token 401.
- Ran `mvn -o -B -f backend/pom.xml -Dtest=GoogleAccessTokenServiceReauthorizationTest test`; result: `BUILD SUCCESS`, 3 tests, 0 failures, 0 errors, 0 skipped. `git diff --check` passed. No full suite, database, Gmail, browser, secret, or service access was performed.
- After integrating draft 401 recovery, the first full run exposed a test fake that did not model the new current-token check; the fake was corrected to identify its synthetic token. The rerun `mvn -o -B -f backend/pom.xml test` passed all 27 tests. The frontend build passed with 2,078 modules, and `git diff --check` passed. No database, live Gmail, or mailbox action was performed for this code change.

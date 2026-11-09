# Phase 2 durable sync-recovery category review

## Priority status

Deferred after the user asked to prioritize Phase 3 readiness. The current-session permission hint is implemented and build-verified; adding a new durable category would require another migration and is not currently a phase-transition gate. Revisit only if the Phase 2 audit identifies reload persistence as a hard requirement.

Date: 2026-11-09
Scope: read-only source audit of Gmail sync failure persistence and the session-only missing-scope reconnect hint. No application code, migrations, tests, builds, Gmail requests, browser state, secrets, or mailbox content were changed or accessed.

## Finding

`GoogleAccessTokenService.getValidAccessToken` throws `AuthFlowException(FORBIDDEN)` only when the connected mailbox's stored `granted_scopes` do not contain `gmail.modify` (`backend/src/main/java/com/east/app/auth/GoogleAccessTokenService.java:47-50`). `GmailSyncService.syncTarget` currently treats every non-401 `AuthFlowException` as a generic `ERROR` and persists only `sync_status` through `GmailSyncRepository.markSyncFailure` (`backend/src/main/java/com/east/app/gmail/GmailSyncService.java:272-282,307-320`; `GmailSyncRepository.java:65-86`). `GET /api/v1/me` returns `MailboxSummary`, which contains status but no failure category (`MailboxRepository.java:49-81`; `MailboxSummary.java`). Manual sync returns a bounded `auth_http_403` response, and the UI stores its permission hint in React state (`ManualMailboxSyncController.java:21-54`; `frontend/src/App.tsx:257,460-465`). After reload, only generic `error` remains, so the Dashboard loses the reconnect/permission explanation.

## Minimal durable design

Persist one internal allowlisted recovery category, `gmail_permission_required`, for the exact local missing-scope condition. Do not persist raw `AuthFlowException` details, Gmail response bodies, or provider error strings. Keep the existing `sync_status` (`error` or `partial_failure`) as the retry/partial-success state; the new field describes the user's next step.

Recommended changes:

- Add Liquibase changeset `009-mailbox-sync-recovery-category` and SQL file `backend/src/main/resources/db/changelog/009-mailbox-sync-recovery-category.sql`. Add nullable `connected_mailbox.sync_failure_code TEXT` with a `CHECK` constraint limited to the documented category. Null means generic/no special recovery step. Register it in `db.changelog-master.xml`; do not edit changeset 008.
- Add a Java enum such as `MailboxSyncFailureCode` with a safe lowercase JSON value. In `GmailSyncService`, classify only the local `AuthFlowException` 403 thrown by missing `gmail.modify` as `GMAIL_PERMISSION_REQUIRED`; keep Gmail API 403s and token-endpoint network/configuration failures in their existing retryable generic handling.
- Pass the enum through `GmailSyncRepository.markSyncFailure`. Preserve the category through failed retries; clear it on successful sync/cursor advancement, a successful mailbox reconnect/upsert, or durable reauthorization status. Preserve the existing per-mailbox transaction and partial-failure logic.
- Add the enum field to `MailboxSummary`; select and map it in `MailboxRepository.listOwnedSummaries`. Add an optional allowlist parser/validator in `frontend/src/lib/auth-api.ts`. `ConnectedDashboard` can then render the existing “Gmail permission needs approval” state and Reconnect action from the durable summary. The manual sync response may keep `auth_http_403` for immediate feedback, but the per-mailbox summary becomes the reload-safe source of truth; remove the duplicate React-only mailbox-ID set from `App.tsx` once callers use the persisted field.

## Focused verification needed

- Extend `GmailSyncStateTest` to inject the specific `AuthFlowException(403)` and assert the safe persisted category alongside retryable status. Also assert a Gmail API 403 does not receive that category, successful sync clears a prior category, and current reauthorization behavior remains unchanged.
- Extend `MailboxSyncStatusJsonTest` (or add a sibling summary serialization test) to prove the DTO emits only the enum's allowlisted string.
- Exercise the Liquibase changeset on a fresh disposable PostgreSQL database through 009 and query only column/check-constraint metadata; if practical, add a guarded PostgreSQL acceptance case for setting/clearing the value. Confirm existing mailbox rows migrate with null category.
- Run Maven tests, frontend production build, `docker compose config --quiet`, and `git diff --check`. Do not induce a real scope failure in the user's mailbox merely to verify this UI; source/build and disposable database evidence are sufficient for this safe category persistence.

## Decision boundary and limits

This is an implementation recommendation, not an authorization to widen OAuth scopes or change product behavior. It does not make a missing-scope error a permanent revoked-token state; reconnect remains an explicit user action, and successful grant replacement followed by sync clears the hint. A non-scope Gmail 403 stays retryable and uses general permission/configuration guidance. No new product decision is required if the accepted status contract is to persist a single internal recovery enum; document the migration in the technical plan and progress log if implemented.

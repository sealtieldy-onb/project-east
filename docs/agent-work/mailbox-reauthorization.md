# Mailbox reauthorization status

## Objective

Persist a safe account-level reconnect-needed status when Gmail's OAuth refresh grant is revoked or expired, distinguish it from transient token-provider failures, expose the status in mailbox summaries, and clear it after a successful relink.

## Status

Implemented and parent-integrated. This workstream did not run builds, tests, database migrations, OAuth flows, or Google/Gmail requests. Parent verification later passed backend compilation and applied migration 005 to both the configured database and a clean temporary database. The app shell and Dashboard now expose reconnect status and a user-triggered reconnect action.

## Changes

- Added Liquibase formatted SQL migration `005-mailbox-reauthorization.sql` to add `connected_mailbox.reauthorization_required BOOLEAN NOT NULL DEFAULT FALSE`. Parent integration included it after color migration 004 in the master changelog.
- `GoogleTokenClient` inspects only the bounded JSON `error` field from an HTTP 400 OAuth response, recognizes the exact `invalid_grant` code, clears its response-byte buffer, and does not propagate provider response content. Transport errors, other HTTP statuses, malformed responses, other provider codes, and oversized bodies remain generic failures.
- `GoogleAccessTokenService` persists the safe flag for `invalid_grant` (and for a missing stored refresh token), rejects future token use for flagged mailboxes, and reports transient refresh failures as a retryable gateway error without marking the mailbox for reconnection.
- `MailboxService` requires a new refresh token before a flagged mailbox can be relinked. A successful upsert clears the flag while preserving the existing refresh-token omission behavior for healthy mailboxes.
- Mailbox summaries now include the boolean `reauthorizationRequired`; `frontend/src/lib/auth-api.ts` validates the field for the parent-owned reconnect banner.
- Parent-owned app-shell presentation now adds a reconnect notice visible in Conversations as well as a per-account Dashboard state.

## Important boundaries

- Only `invalid_grant` from the OAuth token endpoint is classified as credential revocation/expiry. Other HTTP failures and network errors do not persist reconnect-needed state.
- The database stores only a boolean status; OAuth error descriptions and response content are not persisted or returned.
- The status update is owner-scoped by both `app_user_id` and mailbox ID.
- No `.env` was read. No OAuth or Gmail interaction was initiated.

## Handoff

Parent integration follow-up: the reconnect warning is surfaced globally, mailbox summaries refresh after sync failure and on app focus, the UI build passed, and Liquibase migrations 004–005 applied. Reconnection still requires user-initiated Google authorization and remains unverified until then.

# Manual mailbox sync workstream

## Objective

Expose a user-initiated refresh for one connected mailbox through the existing ownership-checked Gmail sync service.

## Status

Controller and response contract added; source review complete. No Gmail calls or Maven compilation were performed.

## Files

- `backend/src/main/java/com/east/app/gmail/ManualMailboxSyncController.java`
- `docs/agent-work/manual-sync.md`

## API behavior

- `POST /api/v1/mailboxes/{mailboxId}/sync` reads `EastUserPrincipal.appUserId()` and delegates to `GmailSyncService.syncMailbox(appUserId, mailboxId)`.
- Successful response: HTTP 200 with `{ "status": "SYNCED", "syncedAt": "..." }`.
- Failure response statuses are coarse and sanitized: 404 / `NOT_FOUND`, 423 / `LOCKED`, 409 / `IN_PROGRESS`, or 502 / `FAILED`. Gmail IDs, access tokens, sync error codes, exception messages, and content are not returned.
- Ownership checks and mailbox-level sync isolation remain inside `GmailSyncService`; this controller does not duplicate that behavior.

## Verification and handoff

- Confirmed there was no existing manual-sync controller and reviewed the nested `MailboxSyncResult` contract and the existing mailbox routes.
- No Gmail API calls, Gmail mutations, tests, or Maven compilation were run. Parent agent can include the controller in the coordinated backend compile after source settles.

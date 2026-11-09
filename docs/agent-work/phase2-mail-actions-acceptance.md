# Phase 2 mailbox actions acceptance audit

Date: 2026-11-09
Scope: read-only source review of mailbox actions and Gmail user-label management. No live Gmail calls, UI actions, builds, or tests.

## Source findings

- The authenticated controller exposes one-thread PATCH, bulk POST, and mailbox-scoped label create/rename/delete routes (`MailActionsController`). The frontend wires actions to explicit button handlers for thread toolbar, star, bulk toolbar, and label manager.
- Supported service actions are archive, trash, untrash, mark read/unread, spam/not spam, star/unstar, and add/remove user labels (`ThreadAction`, `MailActionsService`). System labels cannot be modified because label action lookup requires `label_type = 'USER'`.
- Every target thread is loaded by both local thread ID and `app_user_id`; label operations additionally bind mailbox ID and local label ID. Label management checks that the mailbox is owned before resolving the user label. Access tokens are resolved for the same owner/mailbox pair before Gmail calls.
- Bulk requests enforce 1–100 distinct threads and up to 50 unique label IDs. The service isolates outcomes per thread; the UI keeps failed items selected and presents success/failure counts with mailbox identity. The UI blocks a combined-account bulk label change when selected conversations span accounts.
- Local state is updated only after Gmail accepts the change. If local synchronization fails afterward, the API reports `LOCAL_SYNC_FAILED` (“Gmail changed; local view needs attention” in the UI); a subsequent Gmail-backed list refresh should re-read metadata. Label create/rename/delete also perform the Gmail call before local persistence, so a local DB failure can leave a remote label change that needs refresh/reconciliation.

## Remaining gaps and evidence limits

- `UNTRASH` is implemented in the backend and message strings, but no “Restore from Trash” button or other frontend invocation exists. The product plan only explicitly requires moving messages to Trash, so this is a usability gap rather than a stated Phase 2 acceptance blocker.
- Source review cannot prove that every Gmail action succeeds against the user's accounts. Only Star/Unstar has a user-confirmed live round trip in the progress log. Archive, trash, spam, read/unread, user-label CRUD/application, and bulk partial-failure presentation remain runtime-unverified and require user participation before claiming Gmail action acceptance.
- No application edits were made. No tests were run, per task scope.

# Phase 2 mailbox actions acceptance audit

Date: 2026-11-09
Scope: read-only source review of mailbox actions and Gmail user-label management. No live Gmail calls, UI actions, builds, or tests.

## Source findings

- The authenticated controller exposes one-thread PATCH, bulk POST, and mailbox-scoped label create/rename/delete routes (`MailActionsController`). The frontend wires actions to explicit button handlers for thread toolbar, star, bulk toolbar, and label manager.
- Supported service actions are archive, trash, untrash, mark read/unread, spam/not spam, star/unstar, and add/remove user labels (`ThreadAction`, `MailActionsService`). System labels cannot be modified because label action lookup requires `label_type = 'USER'`.
- Every target thread is loaded by both local thread ID and `app_user_id`; label operations additionally bind mailbox ID and local label ID. Label management checks that the mailbox is owned before resolving the user label. Access tokens are resolved for the same owner/mailbox pair before Gmail calls.
- Bulk requests enforce 1–100 distinct threads and up to 50 unique label IDs. The service isolates outcomes per thread; the UI keeps failed items selected and presents success/failure counts with mailbox identity. Each failed result has an accessible Deselect control so it remains recoverable after refresh moves its row off-page. In combined view, the UI asks for one existing user label per selected mailbox, groups selected threads by mailbox, submits one request per mailbox using that mailbox's label ID, and merges the per-thread outcomes. It does not infer labels by name or create labels implicitly.
- Local state is updated only after Gmail accepts the change. If local synchronization fails afterward, the API reports `LOCAL_SYNC_FAILED` (“Gmail changed; local view needs attention” in the UI); a subsequent Gmail-backed list refresh should re-read metadata. Label create/rename/delete also perform the Gmail call before local persistence, so a local DB failure can leave a remote label change that needs refresh/reconciliation.

## Remaining gaps and evidence limits

- `UNTRASH` is implemented in the backend and is now exposed as **Restore from Trash** in the reader and **Restore to Inbox** in the Trash bulk toolbar. `MOVE_TO_INBOX` is also exposed for archived conversations in All Mail, allowing in-app restoration for Archive/Trash acceptance.
- Source review cannot prove that every Gmail action succeeds against the user's accounts. Star/Unstar, Read/Unread, the full user-label lifecycle (create, rename, apply, remove, and delete), and Spam/Not spam now have user-confirmed live evidence. The user explicitly deferred Archive → Move to Inbox and Trash → Restore; do not prompt for either check again unless reopened. Trash-message behavior remains unverified by user choice; bulk outcomes remain runtime-unverified.
- No application edits were made. No tests were run, per task scope.

## Read/unread row metadata check — source-only review — 2026-11-09

### Source path

- The live list row gets its state from `thread.unread`: `RealThreadRow` applies `.is-unread` to the row (`frontend/src/components/Conversations.tsx:517`), and the CSS bolds its sender and subject (`frontend/src/components/conversations.css:522-523`). The list API response's `unread` field is built from `mail_thread.is_unread` (`MailReadService.java:374-384`; `MailReadRepository.java:69-71`).
- The row has separate selection, open, and star buttons; it has no read/unread action button (`Conversations.tsx:517-559`). Read/unread actions are available in the selected-thread toolbar and bulk selection toolbar (`Conversations.tsx:813-822,1834-1840`). Opening a thread does not itself mark it read; the UI explicitly states this (`Conversations.tsx:813,1951`), and the `GET /api/v1/threads/{threadId}` handler is a read path (`MailReadController.java:44-48`).
- A user-clicked `MARK_READ` removes Gmail's `UNREAD` label; `MARK_UNREAD` adds it (`MailActionsService.java:141-152,183-194`). Only after Gmail accepts the change does the service set local `mail_thread.is_unread` to false/true (`MailActionsService.java:196-210`; `MailActionsRepository.java:179-190`). The target thread is loaded with an `app_user_id` ownership check (`MailActionsRepository.java:21-32`). The single-thread route is authenticated `PATCH /api/v1/threads/{threadId}` (`MailActionsController.java:28-35`); frontend request includes same-origin credentials, CSRF, and `cache: no-store` (`mail-actions-api.ts:60-85,163-170`).
- The `Unread` filter is a GET list query: frontend encodes `unread=true` (`Conversations.tsx:1128-1134,1780-1785`), and the backend uses Gmail's read-only `is:unread` / `-is:unread` query (`MailReadService.java:493-516`). It lists Gmail thread IDs, fetches thread metadata (not bodies), and refreshes local list metadata before mapping the row (`MailReadService.java:250-319`). This GET path does not invoke the mutation handler.
- After a successful single action, the frontend refreshes the list (`Conversations.tsx:1391-1405`). If the thread is currently selected, it also reloads the selected thread detail (`:1397-1398`); that detail GET may fetch message bodies on demand, even though it does not mark them read.

### Safe verification guidance and caveats

- To inspect state without opening a message or changing Gmail, stay on the Conversations list and use the existing row styling or the `Unread` filter / list refresh. These are read-only Gmail metadata/list requests; avoid clicking the row's open button, the reader's `Mark read` / `Mark unread` controls, or the bulk `Read` / `Unread` actions. There is no direct read/unread toggle on a row.
- An unread row is represented by bold sender and subject, and it appears under the `Unread` filter. The filter performs a fresh metadata lookup, so it is more current than an old rendered row after a Gmail-side change. It does contact Gmail read-only; there is no UI-only, local-cache-only freshness check. If a truly offline/local-only check is needed, inspect the existing local `mail_thread.is_unread` record read-only outside the app, but this does not prove Gmail's current label state.
- If Gmail accepts a mutation but the local DB update fails, the service reports `LOCAL_SYNC_FAILED`; the row can be stale until the next read-only Gmail-backed list refresh. Do not repeat the mutation merely to verify it.
- No UI actions, Gmail/API calls, builds, or tests were performed during this source audit. Afterward, the user confirmed that the row-based Read/Unread restoration check worked; no message was opened by the assistant.

## Signed-in list recheck — 2026-11-09

- The localhost East tab is currently signed in, has two connected accounts, and shows the combined Inbox · Primary list at 30 of about 402 conversations. No conversation is selected. No fresh sign-in is needed as of this snapshot.
- The user subsequently confirmed the guided row-based Unread → Read restoration check worked. Treat it as user-confirmed live acceptance; the assistant did not open the row or perform the Gmail mutation.

## Refreshed action-scope audit — source-only — 2026-11-09

### User-click and ownership boundary

- Single-thread mailbox changes are invoked from explicit reader buttons (`frontend/src/components/Conversations.tsx:817-846`) and call `updateThreadAction` from the click handler (`:1926` and `:1388-1406`). Bulk changes are explicit selection-toolbar buttons (`:1834-1859`) that call `runBulkAction` (`:1409-1480`). Label create/rename/delete are separate manager buttons (`:1739-1745`) calling the corresponding handlers (`:1482-1544`). There is no automatic assistant mutation path in Phase 2.
- The authenticated controller exposes single-thread PATCH, bulk POST, and mailbox-scoped label create/rename/delete routes (`backend/src/main/java/com/east/app/mailactions/MailActionsController.java:26-68`). Thread lookup joins the connected mailbox and requires `app_user_id`; user-label lookup also requires mailbox ID and `label_type = 'USER'` (`MailActionsRepository.java:20-67`). The same owner/mailbox pair is passed to token resolution before Gmail calls (`MailActionsService.java:184-194,236-245`). System labels cannot be changed through the user-label endpoints.
- Bulk requests reject empty, duplicate, null, or over-100 thread IDs, and label lists over 50 (`MailActionsService.java:35-67,218-240`). The backend returns a separate outcome for each selected thread (`:42-62`). The UI keeps failed items selected, shows per-account/per-conversation outcomes, and provides per-item deselection in the result list (`Conversations.tsx:1450-1549,1730-1753`). For mixed-account label actions, it requires an explicit existing user-label choice for each represented mailbox and submits mailbox-scoped groups with each mailbox's own label ID (`:1341-1363,1478-1508,1964-1995`). The server independently verifies each requested label against each thread's mailbox.

### Reversibility and caveats

- **Read/unread and star:** each has an inverse action. Verify with the read-only list refresh or filter; do not open the row, since detail GET may hydrate the body.
- **User labels:** add/remove are inverse operations. For create/rename/delete acceptance, use a temporary empty label on a selected account; remove it from any test thread before deleting it.
- **Spam/not spam:** `SPAM` adds Gmail's SPAM label and removes INBOX; `NOT_SPAM` removes SPAM and adds INBOX (`MailActionsService.java:153-160`). Use an ordinary Inbox item with known starting location if testing the pair. `NOT_SPAM` does not restore an originally archived item to its prior folder.
- **Archive and Trash:** Archive removes INBOX; Trash calls Gmail's trash endpoint. The reader now offers Archive, Move to Inbox, or Restore from Trash based on the selected conversation's labels. The Trash-folder bulk toolbar exposes Restore to Inbox, and All Mail exposes Move to Inbox. These are source-implemented. The user deferred Archive → Move to Inbox and Trash → Restore live checks; Trash-message acceptance remains open but should not be prompted again unless reopened.
- Gmail is called before local metadata is synchronized. An upstream success followed by local persistence failure is reported as `LOCAL_SYNC_FAILED`; refresh the Gmail-backed list before deciding whether the action needs retry (`MailActionsService.java:181-210,267-273`). For label create/rename/delete, the same remote-first ordering can leave local state stale if the DB write fails (`:71-129`).

### Safe user-click acceptance sequence

1. Read/Unread is user-confirmed complete for one low-stakes conversation; restoration was reported successful.
2. The user confirmed temporary label creation, rename, application, removal, and deletion. The test thread was restored and the temporary label deleted.
3. Combined-view bulk label apply/remove is source-implemented but has no live acceptance evidence. The user chose to defer this mixed-account bulk-label check on 2026-11-09; do not prompt for it again unless reopened.
4. Spam/Not spam round-trip is user-confirmed. Archive → Move to Inbox and Trash → Restore were explicitly deferred by the user. Other bulk actions remain unverified; guide checks only if the user opts in.

The source paths and ownership checks are present, but runtime action acceptance still requires user clicks for the unverified actions. No browser, Gmail/API, secrets, tests, or builds were used for this refreshed audit. The user later reported their own explicit draft send; the assistant did not perform it and must never send on the user's behalf.

## Live user-label creation — 2026-11-09

- After being guided to select one account and create a temporary label in **Manage user labels**, the user reported “labels work.” Record label creation as user-confirmed live behavior. The assistant did not create the label or change mailbox data.
- The user confirmed the full temporary-label lifecycle: create, rename, apply, remove, and delete. The assistant did not perform any of those Gmail changes or open message content.

## Spam round-trip — 2026-11-09

- The user reported that marking a selected Inbox conversation Spam and then Not spam worked. Treat the reversible pair as user-confirmed live acceptance; the user made the Gmail changes and restored the conversation to Inbox. No message body was opened by the assistant.

## In-app restore controls — 2026-11-09

- Added `MOVE_TO_INBOX` to the explicit user-action API and backend enum. The conversation reader now shows Archive for Inbox threads, Move to Inbox for archived/non-Inbox threads, and Restore from Trash for trashed threads. It omits the restore control for Spam threads, where Not spam is the appropriate inverse action.
- The bulk toolbar exposes Restore to Inbox in Trash and Move to Inbox in All Mail. The user's click remains required; no Gmail action was performed for this implementation.
- `npm run build` passed and Maven offline compile passed for 116 backend sources with tests skipped. These controls still need live acceptance.
- The user deferred the Archive → Move to Inbox live acceptance check. Do not prompt for it again unless the user reopens that check.
- The user deferred the Trash → Restore live acceptance check on 2026-11-09. No conversation was opened or changed for this check; do not prompt for it again unless the user reopens it.

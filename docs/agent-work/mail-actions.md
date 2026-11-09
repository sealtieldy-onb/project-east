# Gmail mailbox actions workstream

## Objective

Add authenticated Gmail thread actions and user-label management routes that validate local ownership, call Gmail only through `GmailApiClient`, and update local metadata only after Gmail confirms the mutation.

## Status

Implementation added and source-reviewed. Maven compilation and Liquibase migration verification are complete. No Gmail API calls or mailbox mutations were made.

## Files

- `backend/src/main/java/com/east/app/mailactions/ThreadAction.java`
- `backend/src/main/java/com/east/app/mailactions/ThreadActionRequest.java`
- `backend/src/main/java/com/east/app/mailactions/BulkThreadActionsRequest.java`
- `backend/src/main/java/com/east/app/mailactions/ThreadActionOutcome.java`
- `backend/src/main/java/com/east/app/mailactions/BulkThreadActionsResponse.java`
- `backend/src/main/java/com/east/app/mailactions/MailLabelRequest.java`
- `backend/src/main/java/com/east/app/mailactions/MailLabelResponse.java`
- `backend/src/main/java/com/east/app/mailactions/MailActionsException.java`
- `backend/src/main/java/com/east/app/mailactions/MailActionsRepository.java`
- `backend/src/main/java/com/east/app/mailactions/MailActionsService.java`
- `backend/src/main/java/com/east/app/mailactions/MailActionsController.java`
- `backend/src/main/java/com/east/app/mailactions/MailActionsExceptionHandler.java`
- `docs/agent-work/mail-actions.md`

## API contract

- `PATCH /api/v1/threads/{threadId}` with `{ "action": "...", "labelIds": [...] }` supports `ARCHIVE`, `TRASH`, `UNTRASH`, `MARK_READ`, `MARK_UNREAD`, `SPAM`, `NOT_SPAM`, `STAR`, `UNSTAR`, `ADD_LABELS`, and `REMOVE_LABELS`.
- `POST /api/v1/threads/bulk-actions` accepts 1–100 distinct `threadIds`, one action, and up to 50 local user-label IDs. It processes exactly those IDs and returns ordered per-thread outcomes; one failed item does not prevent later items from being attempted.
- `POST /api/v1/mailboxes/{mailboxId}/labels` creates a user label. `PATCH /api/v1/mailboxes/{mailboxId}/labels/{labelId}` renames one. `DELETE` on the same path deletes one. Label endpoints use local East UUIDs in paths/responses.
- All routes use `@AuthenticationPrincipal EastUserPrincipal`; token retrieval is coded against the parent-provided contract `GoogleAccessTokenService#getValidAccessToken(UUID appUserId, UUID mailboxId)`.

## Behavior and decisions

- Thread lookups join `mail_thread` to `connected_mailbox` and scope by principal `appUserId`. Mailbox and custom-label lookups use the same owner scoping. System labels cannot be created, renamed, deleted, or passed as user-label IDs.
- Thread mutations are sent individually with Gmail thread operations to preserve per-item outcomes. Bulk does not call Gmail's all-or-nothing batch endpoint.
- Archive removes `INBOX`; trash/untrash use Gmail's dedicated thread methods; read/unread, spam/not-spam, star/unstar, and label changes use Gmail thread label modifications.
- Local `mail_message_label` associations and thread unread/starred flags update only after Gmail returns success. Each thread's local label/flag update is transactional so successful items can commit independently.
- Bulk size is capped at 100 threads and 50 labels per action; duplicate thread or label IDs are rejected. Label names are trimmed and capped at 225 characters.
- API errors and bulk outcomes expose only stable codes and sanitized Gmail status/code fields. The implementation does not log tokens, content, recipient data, or response bodies and does not retry mutations.

## Assumptions and handoff

- The OAuth token service has since landed in shared source; its package, class, and `getValidAccessToken(UUID appUserId, UUID mailboxId)` signature match the implementation contract.
- A user label belongs to one mailbox. In a cross-mailbox bulk action, a label ID that is not a user label for a selected thread's mailbox produces a `LABEL_NOT_FOUND` outcome for that item; other selected items continue.
- Local `mail_label` rows are expected to include the relevant Gmail system-label rows for label associations to reflect archive, spam, trash, and similar actions. Thread `is_unread` and `is_starred` flags are updated directly for the matching actions.

## Verification

- Reviewed current principal, Gmail client, mailbox/thread/label schema, security filter configuration, and route implementation.
- No live Google calls, Maven build, or tests were run. Wait for the parent to settle the OAuth/Gmail client source and coordinate the shared backend build.

## Phase 2 backend source audit (2026-11-09)

Read-only audit against current source. No `.env` or mailbox data was inspected; no live Gmail calls, mailbox actions, builds, or tests were performed. Line references are to the current source tree.

### Findings requiring follow-up

1. **P1 sync timestamp/schema mismatch — resolved in current source; migration/runtime verification is complete.** Initial/history sync sets the normal expiry to `cachedAt + TTL` (`backend/src/main/java/com/east/app/gmail/GmailSyncService.java:443-449`). `toCachedMessage` now splits `bodyExpiresAt` and `snippetExpiresAt`: the body gets the immediate marker only for `ON_DEMAND` or `SYNC_LIMIT` with `syncLimitRetryOnOpen=true`, while every other body keeps `expiresAt` (`:570-587`). `CachedMessage` carries those separate timestamps plus the retry flag (`GmailSyncRepository.java:429-444`). The repository persists body expiry independently (`:195-239`) and maps retry-enabled `SYNC_LIMIT` to the distinct `east-internal:SYNC_LIMIT_ON_DEMAND` value; non-retry `SYNC_LIMIT` remains `east-internal:SYNC_LIMIT` (`:242-250`). Thus, for the background-sync placeholders where `body_cached_at` and batch `syncedAt` are equal (`GmailSyncService.java:305-310,314-317`; repository `:238-239`), equality is now permitted only for `east-internal:ON_DEMAND` and `east-internal:SYNC_LIMIT_ON_DEMAND` by changeset 006 (`006-immediate-body-expiry.sql:3-14`); normal `SYNC_LIMIT` and other reasons must expire later. Migration 006 replaces the strict check from 001 (`001-initial-local-schema.sql:87-99`) and is ordered after 001-005 in the master (`db.changelog-master.xml:29-40`). Snippets remain separately TTL-bound: the service always sets `snippetExpiresAt` to `expiresAt` (`GmailSyncService.java:585-587`), repository writes it to `mail_thread.snippet_expires_at` (`GmailSyncRepository.java:146-192`), and reads expose it only before expiry (`MailReadService.java:371-386`). **Status:** source-level behavior and constraint agree; changesets 001–006 applied successfully to the configured database and a clean temporary database, and Maven compilation passed for all 113 Java sources. The temporary database was dropped. No tests were run.

2. **P2, mailbox disconnect can leave Phase 3 derived records behind.** `MailboxService.disconnect` deletes the owner-scoped `connected_mailbox` (`backend/src/main/java/com/east/app/auth/MailboxService.java:103-111`; repository predicate in `MailboxRepository.java:178-183`). Message and thread rows cascade from the mailbox (`001-initial-local-schema.sql:46-50,73-100`), and insight-source links cascade from messages (`:249-252`). But `summary_run` is only keyed to `app_user_id` (`:210-230`), and `assistant_insight` points to a summary run rather than a mailbox (`:234-245`). Once Phase 3 writes derived records, deleting their source messages removes the source links but does not itself delete the run/insight rows. The current retention cleanup removes derived rows when a linked source body expires (`RetentionCleanupRepository.java:18-46`), but disconnect is a different deletion path. Add mailbox-scoped provenance/purge behavior before AI summaries/insights are enabled; Phase 3 is not implemented in this audit.

3. **P2, send reconciliation relies on the user’s Sent-folder check.** `DraftService.send` requires explicit `confirmed`, the expected draft version, and a review fingerprint before calling Gmail (`backend/src/main/java/com/east/app/drafts/DraftService.java:362-404`; preparation/fingerprint and pending state at `:449-515`). Uncertain operations stay pending and the endpoint requires the user to confirm they checked Gmail (`:545-599`). For a completed send, the backend then removes the pending row based on that confirmation (`:575-582`) rather than independently locating and matching the sent message. This avoids an automatic retry that could double-send, but completion is a manual assertion. Keep the UI wording clear that the user must verify Gmail Sent and do not add automatic retry; consider stronger matching if Gmail sent search can be made reliable.

### Verified positives

- **OAuth and account ownership:** linking has a distinct, session-bound intent (`GoogleOAuthService.java:52-64`), random one-use state/nonce expires after ten minutes (`OAuthSessionStateStore.java:19-22,31-70,102-113`), and callback checks the pending owner against the current principal before linking (`GoogleOAuthCallbackController.java:49-63`). Mailbox deduplication is by owner plus Google subject, and refresh tokens are encrypted with mailbox-specific AAD before persistence (`MailboxService.java:32-95`; schema uniqueness `001-initial-local-schema.sql:22-43`). Access-token lookup is owner-bound and checks the Gmail modify scope (`GoogleAccessTokenService.java:42-53`). Disconnect is an explicit authenticated DELETE and owner-scoped delete (`MailboxController.java:49-55`; `MailboxRepository.java:178-183`); it invalidates the local token cache. No remote Google revocation was verified or performed.
- **Sync cadence and bounded initial sync:** Gmail sync is scheduled at startup and defaults to a 900,000 ms fixed delay, with one-mailbox failures isolated (`GmailSyncService.java:87-120`). Initial sync caps the baseline at 30 messages (`:51,269-310`), captures its Gmail history cursor before enumeration, and persists the cursor only after data persistence (`GmailSyncRepository.java:68-86,94-109`). Scheduled Gmail work is read-only; the only other scheduled jobs found are token-cache cleanup and local retention cleanup (`rg @Scheduled` source inspection).
- **Encrypted short-lived retention:** background sync requests message structure without MIME body `data` (`GmailApiClient.java:557-575`); on-demand reads fetch body data, while cache payloads/snippets are encrypted and Gmail part data is cleared (`GmailSyncService.java:443-500,540-589`). The database default TTL is seven days and configurable from 1 to 365 (`001-initial-local-schema.sql:11-20`; `GmailSyncRepository.java:44-53`). Cleanup deletes expired derived rows and attachment bytes and clears body/snippet ciphertext while retaining metadata (`RetentionCleanupService.java:24-33`; `RetentionCleanupRepository.java:18-87`). Attachments are fetched on authenticated user request, owner-checked and encrypted for the same configured lifetime (`AttachmentContentService.java:50-122,156-184`); responses are `no-store`, attachment-disposition, and `nosniff` (`AttachmentContentController.java:30-47`).
- **Mailbox listing and search:** folder/category/filter pages and explicit searches now use Gmail per owned mailbox, paginate metadata only, and merge/sort candidates (`MailReadService.java:95-101,183-326`). Search supports Gmail query syntax plus folder/category/unread/starred/labels and caps query length at 500 characters (`:469-515`). Bodies are fetched only when a conversation is opened. Cursors are process-local/in-memory, so an app restart invalidates an in-progress page token; no durable cursor requirement was found.
- **User action for Gmail changes and send:** thread action and bulk action routes require the authenticated principal (`MailActionsController.java:28-42`); service resolves threads and user labels under that owner before each Gmail call (`MailActionsRepository.java:21-59`; `MailActionsService.java:132-210`). Create/rename/delete label routes are likewise authenticated and owner-checked (`MailActionsController.java:44-69`; service `:71-129`). The scheduler has no archive/read/label/send calls. Draft routes are authenticated (`DraftController.java:34-115`); save-to-Gmail is a dedicated explicit POST (`DraftService.java:322-360`), while send additionally requires a confirmation flag and exact reviewed version/fingerprint (`:362-404,449-515`). Server security requires authenticated `/api/**` routes and CSRF (`LocalSecurityConfiguration.java:73-98`), plus trusted loopback origins for state-changing requests (`LocalRequestBoundaryFilter.java:49-64`).
- **Draft and attachment controls:** save-to-Gmail and send set a pending side-effect state before the external operation and surface uncertain outcomes for reconciliation (`DraftService.java:322-410`; migration `003-draft-side-effect-reconciliation.sql:8-49`). Saved-draft reconciliation verifies the Gmail Draft by ID or matching review fingerprint (`:605-680`). Outgoing user-selected attachments accept arbitrary extensions, validate filename and MIME header syntax, and fall back to `application/octet-stream` (`DraftAttachmentPolicy.java:7-45`); current caps are 10 files, 10 MiB each, and 16 MiB total (`:7-11`). Forwarded attachments are included/staged under those same caps (`DraftSourceService.java:282-355`); no attachment bytes are passed to AI in Phase 2 because AI analysis is not implemented. Incoming attachments are returned only as downloads by the backend (`AttachmentContentController.java:30-47`), leaving PDF/image inline preview to the frontend.
- **Loopback and schema:** Spring fails closed unless the API binds to `127.0.0.1` (`LoopbackServerConfiguration.java:14-39`), request Host/Origin checks permit only local origins (`LocalRequestBoundaryFilter.java:69-101`), PostgreSQL binds to `127.0.0.1` in Compose (`compose.yaml:1-19`), and JDBC is configured to the loopback address (`application.yaml:9-16,30-33`). Liquibase master currently includes changesets 001-006 (`db/changelog/db.changelog-master.xml:5-40`), covering the singleton local user, owned mailbox/message/cache/label/draft data, and audit-relevant constraints. Changesets 001–006 were applied to the configured local database and a clean temporary database; the temporary database was dropped.

### Next step

Migration 006 and compilation are runtime-verified. Before enabling Phase 3 AI storage, define mailbox-scoped provenance or disconnect purge for summary runs and insights. Real OAuth/Gmail workflow validation (including a user-reviewed test save/send) remains outside this read-only audit.

# Conversations attachment UI and API scratchpad

## Objective and scope

Add user-triggered attachment preview and download controls in the connected Conversations reader, plus its ownership-scoped server content route. Preview PDFs and common raster image types inline where supported. Keep the fictional Phase 1 demo visual-only. No real Gmail calls or tests are performed during implementation.

## Status

Initial frontend/backend implementation and the 2026-11-09 attachment privacy/retention follow-up are complete in source. Parent source review passed; offline Maven compilation passed for 113 Java sources and the frontend production build passed with 2,078 modules transformed. Browser, Gmail-field-mask, and retention runtime verification remain pending. No tests were added or run.

## Contract and behavior

- `GET /api/v1/threads/{threadId}/messages/{messageId}/attachments/{attachmentId}` returns raw bytes and a MIME `Content-Type`; the IDs are local East UUIDs from the thread/message/attachment metadata.
- Requests use same-origin credentials, `cache: no-store`, an abort controller, and a 60-second timeout. The UI does not inspect or log file content.
- Preview/download fetch occurs only after the user clicks. Supported inline MIME types are PDF and common raster images (AVIF, BMP, GIF, JPEG, PNG, WebP); SVG and other types remain download-only.
- PDF previews use a sandboxed iframe without script permission; raster images use an `<img>`. The user can download from the preview dialog.
- Downloads use the server response Blob and a sanitized filename from attachment metadata. Preview Blob URLs are revoked when the dialog closes or unmounts; download URLs are revoked after the browser begins the download.
- An attachment with missing East IDs or no message association is displayed without active actions. Expiry, lock, authorization, and server errors receive safe user-facing messages.
- Background Gmail sync requests MIME structure and attachment metadata with recursive partial-response field masks that exclude all MIME `body.data`. It caches an `ON_DEMAND` body marker with immediate expiry so opening a conversation triggers a per-message fetch.
- Thread opening fetches each message body individually with a bounded response. East extracts text/plain and text/html bodies, then removes all `body.data` fields before encrypting the body cache. Attachment metadata is extracted from the same response, including the Gmail `partId` for named inline parts.
- A Gmail external `attachmentId` remains unchanged. A named inline part with `partId` and no external ID is stored as the reserved string `inline:<partId>` in attachment metadata. The HTTP route still receives local East UUIDs. The inline content path fetches only the selected message, identifies the selected `partId`, decodes only that part, and returns/caches only its bytes. The explicit forward flow recognizes the same inline ID.
- Google field selectors apply to every member of an array and do not support selecting an array element by `partId`. Therefore opening a message body can transiently receive `body.data` for its file parts, and an inline attachment click can transiently receive all MIME `body.data` fields in that message (not just same-depth siblings). East removes the data fields from the JSON tree and retains only message text for body reads or the selected part for attachment reads. The source response byte buffer is zeroed; Jackson text values are immutable and can only be dereferenced for garbage collection, not overwritten in place. This does not persist or return other attachment bytes and no attachment data is sent to AI.
- Recursive field masks cover at most 24 MIME part nesting levels. Deeper parts are not surfaced by current sync/read/inline-fetch paths. Explicit inline requests are bounded by the Gmail response and East's attachment size limits; a message with large total inline data may fail safely even if the selected part is smaller.

## Backend behavior

- `GET /api/v1/threads/{threadId}/messages/{messageId}/attachments/{attachmentId}` accepts local East UUIDs and returns `ResponseEntity<byte[]>` with sanitized MIME type, safe UTF-8 `Content-Disposition` filename, `Cache-Control: no-store`, and `X-Content-Type-Options: nosniff`.
- The repository verifies the current app user through the full `app_user` → `connected_mailbox` → `mail_thread` → `mail_message` → `mail_attachment` join. Missing/deleted or cross-owner records return the same safe 404.
- A fresh encrypted cache hit is decrypted only while the local vault is unlocked. On a cache miss or expired ciphertext, the route fetches Gmail bytes only for this explicit request, encrypts them, and refreshes expiry using the current configurable body-cache TTL. If retention has already deleted the metadata row, the endpoint returns 404 and the UI asks the user to refresh the conversation.
- Associated data is scoped to app user, mailbox, Gmail thread, Gmail message, and Gmail attachment IDs. A version byte inside the encrypted payload allows zero-byte files to satisfy the database's minimum ciphertext size. Temporary plaintext, associated encrypted nonce/cipher arrays, fetched bytes, and cache envelope buffers are wiped after use; the response body remains only as the byte array required for HTTP serialization.
- The route rejects files over 50 MiB, maps Gmail missing/authorization/upstream failures to generic safe messages, and never logs filenames, message content, tokens, or file bytes.
- Attachment filenames are sanitized at Gmail metadata ingestion, again when returned by thread-read APIs, and before setting `Content-Disposition`. Unicode format controls (including bidi overrides and isolates), C0/C1 controls, and path separators are removed/replaced; empty, `.` and `..` names use the safe `attachment` fallback.
- Cache lookup now also reads `mail_message.body_expires_at`. Pre-expiry attachment bytes are not served after their source body expires, even before the scheduled purge. An explicit click refetches from Gmail and starts a fresh attachment TTL; the cleanup query preserves only such post-expiry refetches until their own expiry, and deletes unrefetched/stale metadata at source expiry.
- Implementation spans `frontend/src/components/Conversations.tsx`, `frontend/src/lib/conversations-api.ts`, `backend/src/main/java/com/east/app/attachments/`, `backend/src/main/java/com/east/app/gmail/GmailApiClient.java`, `GmailSyncService.java`, `GmailSyncRepository.java`, `backend/src/main/java/com/east/app/mailread/MailReadService.java`, `backend/src/main/java/com/east/app/drafts/DraftSourceService.java`, and `backend/src/main/java/com/east/app/retention/RetentionCleanupRepository.java`. It did not modify `GmailCacheAad` or schema/migrations.

## Verification

- After the 2026-11-09 follow-up, offline Maven compilation passed for 113 Java sources and `npm run build` passed with 2,078 modules transformed.
- No tests were added or run for the follow-up, and no live Gmail calls were made.
- Follow-up source review fixed two backend findings: expiry cleanup now runs while locked without decrypting, and filename sanitization strips Unicode format/bidi controls at ingestion and output. No tests were run for these fixes.
- Static parent review passed the main attachment source paths. The backend compile and frontend build have passed; no database run, Gmail field-mask request, or live attachment request was performed.

## Files

- `frontend/src/components/Conversations.tsx`
- `frontend/src/components/conversations.css`
- `frontend/src/lib/conversations-api.ts`
- `backend/src/main/java/com/east/app/attachments/AttachmentAad.java`
- `backend/src/main/java/com/east/app/attachments/AttachmentContentController.java`
- `backend/src/main/java/com/east/app/attachments/AttachmentContentException.java`
- `backend/src/main/java/com/east/app/attachments/AttachmentFilenameSanitizer.java`
- `backend/src/main/java/com/east/app/attachments/AttachmentContentService.java`
- `backend/src/main/java/com/east/app/attachments/AttachmentRepository.java`
- `backend/src/main/java/com/east/app/gmail/GmailApiClient.java`
- `backend/src/main/java/com/east/app/gmail/GmailSyncService.java`
- `backend/src/main/java/com/east/app/gmail/GmailSyncRepository.java`
- `backend/src/main/java/com/east/app/mailread/MailReadService.java`
- `backend/src/main/java/com/east/app/drafts/DraftSourceService.java`
- `backend/src/main/java/com/east/app/retention/RetentionCleanupRepository.java`
- `docs/product-plan.md`
- `docs/technical-plan.md`
- `docs/implementation-roadmap.md`
- `docs/decisions-and-open-questions.md`
- `docs/agent-work/retention-cleanup.md`
- `docs/project-progress.md`
- `docs/agent-work/attachment-ui.md`

## Handoff

The parent compile and frontend build passed. Remaining verification requires the user to sign in again after the backend restart and explicitly open attachments or forward a message. Verify the Gmail field-mask response against the connected account, then exercise external and inline PDF/image preview, non-previewable downloads, cache hits, the source-body-expiry/refetch TTL path, cleanup, locked/unauthorized behavior, and deleted-attachment 404. No user-triggered attachment request or forwarding flow has been verified; the success of the new field mask through scheduled sync is not yet confirmed. No migration is needed.

## Broader attachment and draft source audit — 2026-11-09

### Confirmed source behavior

- Scheduled/incremental Gmail sync uses `GmailApiClient.MESSAGE_STRUCTURE_FIELDS`, whose recursive MIME selectors omit every `body.data` field. Message bodies are marked `ON_DEMAND`; opening a thread triggers per-message full body reads.
- The on-demand reader extracts text and attachment metadata, then removes each MIME `body.data` node in a `finally` block before serializing/encrypting the message cache. Attachment bytes from the thread response are therefore not written into the body cache or returned in the thread API.
- Standard Gmail attachments use their Gmail `attachmentId` endpoint. Named inline parts use the reserved `inline:<partId>` marker and are fetched only from the explicit attachment endpoint or the user-triggered forward flow. The inline reader clears all MIME data fields and returns only the selected part's bytes. Because Gmail field masks cannot select array entries by part ID, an inline request can transiently transfer every MIME `body.data` field in that message; East retains only the selected part. The recursive field masks stop at depth 24.
- The attachment route verifies ownership through user, mailbox, thread, message, and attachment joins; serves `no-store`, `nosniff`, sanitized `Content-Disposition` and safe MIME type; uses a 50 MiB cap; and encrypts fetched bytes with owner/mailbox/thread/message/attachment-scoped associated data. Preview support is limited to PDF and raster images; PDF uses a script-disabled sandboxed iframe, SVG remains download-only, and Blob URLs are revoked on close/unmount.
- Attachment cache reads compare `fetched_at`, attachment `expires_at`, and source `mail_message.body_expires_at`. The scheduled deletion removes expired bytes and source-expired metadata, while a post-source-expiry user-click refetch is kept only through its own configured TTL. Retention runs without needing to decrypt the cache. Changing the configured body TTL updates live attachment expiries.
- Local selected files and forwarded attachment bytes are staged as encrypted rows under the local draft and are not sent until the user saves or sends the draft. The UI permits removing forwarded files and displays them during send review. Current limits are 10 attachments, 10 MiB per file, and 16 MiB total. Successful save and sent-draft deletion remove the staged rows; deleting the local draft cascades to them.

### Follow-up source re-review — 2026-11-09

- Resolved: `DraftSourceService.fetchMessage` now receives the draft kind and extracts attachment descriptors only for `FORWARD`. A `finally` block clears every MIME `body.data` node after text and (for forwards) attachment metadata are extracted. The forwarded inline bytes still exist in the temporary `SourceAttachment` metadata only for the explicit forward operation; replies no longer create that unused copy. The Gmail response can still transiently contain inline file data during a body read due Gmail's array field-mask behavior.
- Resolved: `AttachmentContentService` catches `GmailResponseTooLargeException` for both inline and external attachment retrieval and maps it to the safe 413 `ATTACHMENT_TOO_LARGE` result.
- Resolved: `DraftSourceService.fetchSourceAttachments` maps oversized inline and external Gmail responses to a safe draft-size error, then verifies actual size against metadata and the 10 MiB per-file / 16 MiB aggregate / 10-attachment limits before staging.

### Final source re-review — 2026-11-09

- Resolved: oversized `format=full` message reads now fall back to Gmail metadata with the selected source headers; an oversized metadata response maps to a safe draft conflict. The fallback preserves forwarded attachment access through the attachment metadata retained by structure-only sync.
- Resolved: `headersFromGmail`, body extraction, and forward-only attachment extraction are all inside the `try/finally` scope that clears MIME `body.data` nodes.
- Resolved: only explicit forwards extract source attachment descriptors. Reply/reply-all reads no longer keep inline attachment data in the source context.
- Resolved: both `AttachmentContentService` and the forwarding fetch path map oversized external and inline attachment responses to safe user-facing errors. Forward bytes are checked against metadata and the 10 MiB per-file / 16 MiB aggregate / 10-attachment limits.
- Resolved: `DraftAttachmentPolicy.normalizeSourceFilename` now applies `AttachmentFilenameSanitizer`, removing path separators, control characters, and Unicode format/bidi controls for uploaded and forwarded filename paths.
- No remaining source-only gap found in the reviewed fixes. Gmail's inline field-mask limitation remains: explicit inline read/forward requests can transiently receive unrelated MIME `body.data`, which East clears before caching or returning only the selected bytes. Current MIME selectors have a depth limit of 24.

### Runtime gap

No live Gmail field-mask, attachment preview/download, cache-expiry/refetch, or forwarding request has been made. These require a user-triggered action and remain deferred until the user is available. No builds, tests, or Gmail calls were performed during this final source re-review.

## Product-plan source audit — 2026-11-09

### Current source evidence

- Background sync uses `GmailApiClient.MESSAGE_STRUCTURE_FIELDS` (`GmailApiClient.java:48-51,564-581`; `GmailSyncService.java:464-469`). The generated recursive field mask requests attachment IDs, sizes, and metadata but omits every `body.data`. Source search found `getAttachmentBytes` / `getInlineAttachment` calls only in the explicit attachment-content service and draft-forward / saved-draft review paths; no scheduled-sync attachment endpoint call exists.
- Opening a thread is on demand (`MailReadService.java:107-138`), then the full body field mask requests `data` from each MIME part (`GmailApiClient.java:48-50,564-581`; `GmailSyncService.java:172-177`). Gmail applies a selector across every array element, so a user-opened thread may transiently transfer small inline MIME attachment data along with its body. `GmailSyncService.toCachedMessage` extracts message text and metadata and clears all MIME `body.data` in `finally` before encrypted cache serialization (`GmailSyncService.java:543-558`). This is not background sync, and those bytes are not retained in the body cache. It is a documented Gmail field-mask limitation; no isolated safe field-mask change can select only text MIME array members.
- Attachment content retrieval occurs after a click in `AttachmentItem.loadAttachment` (`Conversations.tsx:677-715`). The UI offers inline preview only for PDF and raster images (`Conversations.tsx:571-587,684-696`); PDF is rendered in an iframe sandbox without script permission, SVG and other types remain download-only, and the preview Blob URL is revoked when the dialog closes/unmounts (`Conversations.tsx:594-646,670-673`). Downloads are available for all listed attachments.
- The content route is ownership-scoped and responds `no-store` / `nosniff` (`AttachmentContentController.java:30-47`; `AttachmentRepository.java:23-61`). `AttachmentContentService.load` serves only an unexpired encrypted cache hit or fetches Gmail after the explicit request, enforces size and safe MIME/name handling, and wipes temporary byte arrays (`AttachmentContentService.java:50-125,128-188`). The encrypted cache's associated data binds it to the owner, mailbox, thread, message, and Gmail attachment.
- Cache hits are rejected after either the configured attachment expiry or the source message-body expiry (`AttachmentContentService.java:66-83`; `AttachmentRepository.java:75-108,225-232`). A click refetch starts an attachment TTL from `fetchedAt` (`AttachmentContentService.java:160-188`). The settings path updates live attachment expiry on TTL changes (`SettingsService.java:163-179`), and scheduled purge deletes expired content/source-expired metadata every minute (`RetentionCleanupService.java:24-33`; `RetentionCleanupRepository.java:57-70`).
- No attachment-analysis call path was found in the current backend source. The current dashboard explicitly says email is not analyzed or sent to an AI provider in this phase (`ConnectedDashboard.tsx:40`). The Phase 3 attachment-analysis exclusion is therefore satisfied by absence of an analysis path; this audit did not send any content to a provider.
- User-selected files are accepted only by explicit composer selection, bounded to 10 files, 10 MiB per file, 16 MiB total, encrypted with draft-scoped AAD, and retained in app-local drafts (`Composer.tsx:886-920,938-957`; `DraftService.java:265-303,1374-1405`). Local removal is available for each staged file (`Composer.tsx:1241-1250`).
- Forwarding is explicit: only `FORWARD` source reads extract attachment descriptors, fetch and validate attachment bytes against metadata and draft limits, then stage them encrypted (`DraftSourceService.java:223-241,299-393`; `DraftService.java:1346-1372`). The composer tells the user forwarded files are included by default and may be removed (`Composer.tsx:694-717,938-957`), and send review lists each attachment (`Composer.tsx:1163-1181`). Saving to Gmail deletes the local staged attachment rows after the Gmail save is reconciled (`DraftRepository.java:267-284`).

### Result and remaining checks

- No isolated source-only code gap was found against the cited Phase 2 requirements; no application code was changed.
- Residual data-transfer nuance: opening a thread may transiently receive inline part data due Gmail's broad MIME array field-mask semantics. Background sync does not request it; thread hydration strips it before local persistence; the explicit attachment endpoint retains only the selected part. The 24-level MIME selector limit remains documented above.
- No attachment-specific runtime action was performed. User-triggered checks for PDF/raster preview, download-only files, attachment-cache expiry/refetch, and forwarding/removal remain deferred. No Gmail/API calls, builds, or tests were run for this audit.

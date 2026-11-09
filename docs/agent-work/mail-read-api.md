# Gmail read API scratchpad

## Objective and status

Implement authenticated, ownership-scoped read routes for Gmail threads and labels. The read path is implemented in `backend/src/main/java/com/east/app/mailread/`; final verification is a source review only. No Gmail calls were made in this workstream, no schema/migration edits were needed, and Maven was not run per parent instruction.

## Endpoints and response shape

- `GET /api/v1/threads` accepts `mailboxId`, `folder`, `category`, `query`, `unread`, `starred`, `labelId`, `pageToken`, and `pageSize`.
- `GET /api/v1/threads/{threadId}` accepts only the East-owned local thread UUID. It verifies the thread is joined to a mailbox owned by the authenticated `EastUserPrincipal`.
- `GET /api/v1/mailboxes/{mailboxId}/labels` verifies mailbox ownership, refreshes system and user label metadata through Gmail's read-only labels endpoint, and returns `{id,name,color,type}` rows. `type` is `SYSTEM` or `USER`; IDs are East-owned label UUIDs; `color` is null because the current schema/client does not persist Gmail label colors.
- Thread list JSON matches the frontend contract: `{items,nextPageToken,totalEstimate}`. Items include East thread ID, mailbox ID/identity, subject, nullable encrypted-cache-backed snippet, latest sender, timestamp, unread/starred, labels, category, message count, and nullable attachment availability.
- Detail JSON includes account identity, subject, thread labels, chronological messages with East message IDs, sender, To/CC/BCC/reply-to/message-id headers, timestamp, text, sanitized HTML, and attachment metadata. Attachments have East-owned IDs and metadata only; this route never fetches attachment bytes.

## Query and cursor behavior

- Empty `query` reads the local metadata cache with SQL ownership and filters. Local results merge accounts by newest message time.
- Nonempty `query` calls Gmail `threads.list` independently for every selected owned mailbox, follows each mailbox's own Gmail page token, obtains summary fields with `threads.get(format=metadata)` only, and merges candidates by newest message time. It fetches no bodies during list/search. All selected mailboxes must succeed; an account failure returns a safe error instead of silently dropping that account's results.
- Each account retains its own buffered thread IDs and Gmail page token between combined pages. The public page token is a random UUID lookup key held in process memory; it contains no message body, subject, sender, email, or search text. Tokens are one-use, user/query scoped, and expire after 15 minutes. A restart or expired token returns `INVALID_PAGE_TOKEN`, prompting a list refresh.
- Search metadata writes only allowed navigation fields (sender, subject, date, labels) and empty body-cache columns. Older full content is requested only when the user opens a thread.
- Gmail categories derive from stored system labels: `CATEGORY_PERSONAL` → `PRIMARY`; `CATEGORY_SOCIAL` → `SOCIAL`; `CATEGORY_PROMOTIONS` → `PROMOTIONS`; `CATEGORY_UPDATES` → `UPDATES`; `CATEGORY_FORUMS` → `FORUMS`. The frontend mailbox list shape now needs/uses `categories` as these normalized strings; the OAuth mailbox summary/repository was updated by the parent agent.

## Cache, ownership, and Gmail behavior

- Detail reads are read-only and never mark a message read. The thread UUID is resolved through `mail_thread` → `connected_mailbox` → current app user before any Gmail request or decryption.
- Before deciding whether cached detail is complete, the endpoint checks Gmail thread metadata (only From/Subject/Date headers) and compares its message IDs with the local cache. If messages are missing or any body is missing/expired, it invokes `GmailSyncService.loadThreadOnDemand(appUserId, mailboxId, gmailThreadId)` to fetch/cache the full user-opened thread. It does not update the scheduled sync cursor or `last_sync_at`.
- A body placeholder caused by the background-sync byte budget expires immediately so opening its thread retries under the separate per-thread hydration budget. If that on-demand request itself exceeds its limit, its unavailable marker remains cached for the normal TTL to avoid repeating a futile full-thread fetch on each open. `MESSAGE_TOO_LARGE` and `THREAD_LIMIT` markers also retain the normal TTL.
- `GmailSyncRepository.persistOnDemand` persists refreshed content/metadata without cursor advancement. The shared versioned AAD functions are in `GmailCacheAad`: body AAD is UTF-8 `east:gmail-message-cache:v1:user:<appUserId>:mailbox:<mailboxId>:thread:<gmailThreadId>:message:<gmailMessageId>`; snippet AAD is UTF-8 `east:gmail-thread-snippet:v1:user:<appUserId>:mailbox:<mailboxId>:thread:<gmailThreadId>`.
- Message bodies and recipients/detail-only headers are decrypted only while the local encryption key is available and while `body_expires_at` is in the future. HTML is sanitized with a restrictive jsoup safelist before returning; plain text is returned for the reader. Temporary plaintext bytes are wiped after parsing.
- A concurrent mailbox sync may make on-demand hydration return HTTP 409 (`SYNC_IN_PROGRESS`); reopening the thread retries. No read route calls a Gmail mutation API or attachment-byte API. Credentials and message content are not logged.
- Provider results are held in per-mailbox queues across pages rather than discarded. `totalEstimate` sums Gmail's per-mailbox estimates. The implementation assumes Gmail `threads.list` pages are newest-first so one page-size of lookahead per mailbox is sufficient for a globally merged newest-first page; the [official method reference](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.threads/list) documents pagination but does not guarantee ordering. If a strict cross-account newest-first guarantee is required, this bounded lookahead needs an exhaustive scan or a stronger ordering source.
- The parent has wired an explicit `POST /api/v1/mailboxes/{id}/sync` frontend flow after `/me` loads unsynced mailboxes, plus Dashboard Sync/Refresh. This uses the existing bounded initial sync and covers mailboxes linked after startup.

## Files and verification

- Added `MailReadController`, `MailReadService`, `MailReadRepository`, response models, exception handling, and the HTML sanitizer under `backend/src/main/java/com/east/app/mailread/`.
- Added `GmailCacheAad` and the on-demand thread-load/persist-without-cursor path to the existing Gmail sync package because the read API needs the same AAD and encrypted-cache writer.
- Historical implementation note: the initial design checked vault unlock state and avoided extending an idle timer. The current key-file model has no routine lock; operations check that the encryption key is available.
- Source/schema/API contract was inspected. No tests, Maven build, live Gmail calls, or database changes were performed.
- Follow-up fix: `GmailSyncService.toCachedMessage` now distinguishes background sync from on-demand hydration when setting the expiry of a `SYNC_LIMIT` placeholder. Background sync leaves it immediately eligible for on-open hydration; on-demand hydration gives the same marker the configured cache expiry so subsequent opens do not repeatedly fetch a thread known to exceed East's per-thread budget. No tests were run, per parent instruction.

## Handoff

The two `GET /api/v1/threads` routes and mailbox-label route are implemented and ready for frontend use. The explicit bounded sync path handles unsynced mailboxes. The attachment-bytes route is implemented in the attachment workstream; its identifiers are local UUIDs used for ownership-checked lookup.

## Runtime follow-up — 2026-11-09

Live read-only verification after the local reset: one connected mailbox syncs successfully. Full-mailbox search returns 30 of approximately 201 conversations on the first page; pagination loads 60. Search succeeded after increasing the request timeout to 90 seconds and making the result estimate monotonic during pagination. No Gmail mutation or attachment opening/download was performed. A second mailbox and explicit user-click workflows remain for Phase 2 acceptance.

Documentation-only status update: no tests or builds were run for this note. Gmail Drafts save and send remain unverified and were not performed.

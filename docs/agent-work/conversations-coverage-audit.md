# Conversations mailbox coverage audit

## Finding

The default Conversations list is intentionally populated only from East's local metadata cache. `GmailSyncService.initialSync` enumerates at most the latest 30 Gmail **messages per mailbox** (not 30 threads, and not 30 days), then persists those message/thread metadata records. `MailReadService.listThreads` routes a blank `query` to `MailReadRepository.countThreads/findThreadPage`; the displayed `totalEstimate` is therefore only the count of cached threads matching the selected folder/category. It is not Gmail's mailbox count. The UI requests 30 local threads per page and correctly exposes “Load more” only when the local repository has another page.

For the current view, the UI was observed at “Showing 11 of 11 conversations Inbox · Primary”. That proves only 11 cached threads match the combined Inbox/Primary filter; it does not prove the Gmail Inbox contains only 11. The selected default scope/category can further narrow the rows.

The existing full-mailbox search path is different: a nonempty search term calls Gmail `threads.list`, follows Gmail pagination per mailbox, gets metadata for each result, then hydrates bodies only when a user opens a thread. Prior scratchpad reports one mailbox search at 30 then 60 of about 201 matches. Thus the missing coverage is in default folder/category browsing, not the Gmail search path or the 30-row frontend page limit.

## Product contract

Product/technical docs intentionally bound the initial background sync to the latest 30 messages per mailbox. They also require Conversations to search the full Gmail mailbox on demand, including older results. They do not currently say that a normal Inbox/label/folder list should fetch all Gmail metadata. This behavior is consistent with the bounded-sync/search contract but is surprising for the Gmail-style workspace and the total count label overstates the scope unless it is explicitly described as local.

## Safe remediation options

1. Preferred UX + bounded remote reads: make folder/category/filter listing use the existing paginated Gmail metadata search path (including account scope) by default, so opening Inbox retrieves all matching thread metadata page-by-page, with bodies still fetched only on user open. Preserve the bounded 30-message *background body sync*; the list fetches metadata on demand. The current `buildGmailQuery` already creates Gmail folder/category/read/star clauses, and `threads.list` pagination/cursors already exist. Add an explicit remote-list mode or route rather than overloading blank `query`, normalize folder/category/label filters consistently, and avoid a misleading local SQL total.
2. Minimal change: present a clear local-sync coverage count/label and add an explicit “Load older from Gmail” action that starts metadata-only paging. This avoids automatic full mailbox enumeration but still makes the coverage gap visible and recoverable.

Any implementation must keep per-mailbox cursors and stable mailbox association, check ownership before fetching, avoid bodies/snippets beyond current retention behavior, merge account results carefully, and show per-account errors rather than silently omit a mailbox. It must not widen initial body caching beyond the user-confirmed 30-message baseline.

## Verification inventory

- Source inspection only; no Gmail calls, writes, or app code edits were made in this audit.
- No backend test source tree was found in `backend/` during a path scan.
- Existing endpoints: `GET /api/v1/threads` with query/folder/category/filter/page token; blank query is local SQL, nonblank query is Gmail-backed.
- The frontend load-more button works only when the returned page has a next token; current 11-of-11 has no next token because the local database count is 11.

## Resolution — 2026-11-09

Implemented the preferred option: every thread-list request uses the existing per-mailbox Gmail metadata pagination path. The initial body-sync cap remains 30 messages per mailbox, and bodies remain on-demand when a user opens a thread. Maven compilation and the frontend production build passed. Live combined Inbox/Primary returned 30 of about 402 conversations and reached 60 after Load more; rows from both accounts were visible. No thread was opened and no mailbox change was made during verification.

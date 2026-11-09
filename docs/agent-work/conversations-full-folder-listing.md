# Conversations full folder listing

## Objective

Let normal Conversations folder/category/filter views page beyond the 30-message initial sync cache while preserving metadata-only browsing and on-open body hydration.

## Finding and implementation

- `MailReadService.listThreads` used local SQL whenever the search box was blank. Since ordinary folder browsing is blank-search, Inbox/category pages stopped at locally cached messages even when Gmail had much more.
- All thread list requests now use the existing per-mailbox Gmail metadata pagination and merge selected accounts by latest thread time. Gmail counts are approximate; the UI calls them estimates.
- `getThreadPage` now allows 90 seconds for folder and search pages because a combined page resolves metadata for results from multiple accounts.
- The initial sync remains capped at 30 messages per mailbox. Folder pages store only metadata; full message bodies are still fetched when the user opens a conversation. The Phase 3 consent rule for retrieving bodies beyond the baseline remains.

## Verification

- `mvn -o -B -f backend/pom.xml -DskipTests compile` — passed, 113 Java sources.
- `npm run build` — passed, 2,078 modules transformed.
- Live East after backend restart/re-sign-in: Combined Inbox/Primary loaded 30 of about 402 estimated conversations. Clicking Load more reached 60; rows from both connected accounts appeared. No thread was opened and no Gmail mutation occurred during this check.
- Restarting the backend cleared the in-memory local session; Google sign-in succeeded again.
- No tests were added or run.

## Next

- User deferred attachment checks. Continue other Phase 2 acceptance workflows only with the user's explicit participation for Gmail changes, saves, or sends.
- Keep Phase 2 active until required user-click workflows are verified; do not start Phase 3 early.

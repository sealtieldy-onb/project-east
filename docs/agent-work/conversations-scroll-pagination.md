# Conversations scroll pagination

## Objective

Make older conversations appear naturally as the user scrolls, without pulling the entire mailbox or full message bodies into the app at once.

## Implementation

- `frontend/src/components/Conversations.tsx` observes a sentinel at the end of the scrollable conversation list.
- When the sentinel enters the list viewport, East requests the next existing bounded page (30 threads). The manual Load more button stays available as a fallback.
- A synchronous in-flight guard prevents the observer and manual fallback from consuming the same page cursor concurrently; stale failures from a prior filter are ignored.
- If the backend has restarted or a cursor otherwise expires, East recognizes the `INVALID_PAGE_TOKEN` response and rebuilds the already-loaded pages plus the requested next page from a fresh first-page cursor. Other request errors remain visible.
- Inbox category tabs include an explicit All categories choice, and the footer says scrolling loads the next 30 conversations while another page exists.
- Message-body retrieval remains on thread open; pagination uses the existing Gmail metadata list path.

## Verification

- `npm run build` passed: TypeScript compilation and Vite production build, 2,078 modules transformed.
- Live read-only check: combined Inbox/Primary increased from 30 to 60 and then 90 of about 402 estimated conversations after scrolling near the end. Both connected accounts remain in the combined scope.
- After the in-flight guard change, the rebuilt frontend reloaded and returned a 30-row page in the current Inbox view; scrolling then loaded rows 31–60. The UI did not show a service error.
- **2026-11-09 cursor recovery:** The live All accounts · Inbox · Social view had 60 of about 402 conversations. Clicking Load more returned HTTP 400, consistent with a stale in-memory cursor after the backend restart. Refreshing the list created a fresh cursor; loading another page succeeded and the UI showed 60 again, then scroll pagination advanced to 90. The frontend now recognizes the backend's `INVALID_PAGE_TOKEN` code and reconstructs the currently loaded pages plus the requested page, rather than failing with a generic 400. The production frontend build passed after this change. The automatic stale-token recovery branch was source-reviewed; the live recovery check used an explicit read-only list refresh because refreshing replaced the stale token before the new branch could be exercised.
- Added an explicit All categories tab so the selected Gmail category cannot be mistaken for the entire Inbox. The footer now says “scroll to load next 30.” A rebuilt live UI shows the new tab and 90 of about 402 for the current Social view.
- Live read-only check after selecting All categories: the combined Inbox loaded 60 of about 402 conversations, with rows from both connected accounts. The All accounts scope remained selected. The page count is an estimate; each page contains up to 30 threads.
- **2026-11-09 per-account follow-up:** In a signed-in read-only session, each account-specific Inbox · Primary scope returned 30 of about 201 conversations; the restored All accounts scope initially returned 30 of about 402 and advanced to 90 after Load more/scroll pagination. The read-only **All mail · All categories** view also returned 30 of about 402; this is a separate Gmail estimate for that folder scope. Only the first 30 rows are requested initially; scrolling or **Load more** requests further metadata pages. No sign-in was needed, no thread was opened, and no mailbox state was changed.
- **2026-11-09 session-expiry follow-up (historical):** The live combined Inbox still had 60 loaded against an estimate of about 402. Clicking Load more returned HTTP 401. Reloading the page safely returned East to its Google sign-in screen, confirming the local app session was no longer valid; the exact origin of that earlier 401 (app session versus one mailbox authorization) was not isolated then. The current folder was Inbox, so this estimate did not include archived mail in All Mail. No mailbox action, message opening, or attachment access occurred. The sign-in was later completed; the subsequent signed-in per-account and combined checks are recorded above.
- The frontend now maps 401s to distinct recovery guidance for an expired East sign-in and a mailbox that requires reconnection. The list error shows **Reload East** for either case so the user reaches the sign-in screen or the Dashboard reconnect notice, instead of repeatedly retrying a stale list. `npm run build` passed (2,078 modules transformed). Live verification of either branch is pending because both open East tabs currently show the sign-in page; no Gmail mutation or message/attachment access occurred.
- **Local service retry — 2026-11-09 (historical):** On the localhost tab, the UI initially showed the stale vault-service-unavailable notice. Read-only checks through Vite's same-origin proxy returned vault `{initialized:true, unlocked:true}` and Google OAuth configuration `true`. Clicking the page's **Retry** cleared the notice and showed **Continue with Google**. At that time, the app was signed out. This verified the local services were reachable; it did not identify whether the earlier list 401 came from the expired East session or a mailbox token. No Gmail mutation occurred in that check.
- No message body or attachment was opened. No Gmail mutation or send occurred.

## Notes

- Page counts remain estimates returned by Gmail. Search scope and folder/category filters still determine which conversations are counted.
- The active category is part of the scope: in this live check the selected view was Social, not the entire Inbox. The account scope was All accounts.
- The initial body-cache sync limit is unchanged. Older bodies are fetched only when a thread is opened.

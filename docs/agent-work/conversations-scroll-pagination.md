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
- **2026-11-09 extended pagination check:** The combined Inbox advanced in 30-row pages through 660 loaded rows while Gmail continued to estimate about 402; no 401 or visible list error occurred. On the next page attempt, the summary briefly showed 0 of 0 and then a fresh first-page state of 30 of 402 with Load more available. The session remained signed in. This unexpected list reset needs reproduction/diagnosis before claiming stable end-of-cursor behavior. No thread was opened and no Gmail state changed.
- **2026-11-09 reauthentication follow-up:** After the user signed in again, the read-only Dashboard showed both connected accounts as Synced. Conversations loaded 30 of about 402 Inbox · Primary rows; clicking **Load more** completed successfully and advanced to 60 of about 402. The list showed its next-page loading state while the metadata request was in flight. No thread was opened and no Gmail state changed. This confirms one page fetch after reauthentication; cursor exhaustion remains unverified.
- **2026-11-09 continuous cursor check and count copy:** In the same combined Inbox · Primary scope, repeated **Load more** requests advanced the loaded count from 30 to 1,590 without a request error or the earlier reset at 660. The footer still offered another page, so the cursor's end state was not observed. Gmail's displayed estimate remained around 402 while loaded rows exceeded it substantially. The frontend now describes loaded count and next-page availability without treating or displaying the estimate as a total. `npm run build` passed (2,078 modules transformed). HMR returned the current view to its first 30 rows; the updated UI showed “30 conversations loaded · scroll to load next 30.” No thread was opened and no Gmail state changed.
- **2026-11-09 session-expiry follow-up (historical):** The live combined Inbox still had 60 loaded against an estimate of about 402. Clicking Load more returned HTTP 401. Reloading the page safely returned East to its Google sign-in screen, confirming the local app session was no longer valid; the exact origin of that earlier 401 (app session versus one mailbox authorization) was not isolated then. The current folder was Inbox, so this estimate did not include archived mail in All Mail. No mailbox action, message opening, or attachment access occurred. The sign-in was later completed; the subsequent signed-in per-account and combined checks are recorded above.
- The frontend now maps 401s to distinct recovery guidance for an expired East sign-in and a mailbox that requires reconnection. The list error shows **Reload East** for either case so the user reaches the sign-in screen or the Dashboard reconnect notice, instead of repeatedly retrying a stale list. `npm run build` passed (2,078 modules transformed). Live verification of either branch is pending. At the time of the earlier diagnostic, both open tabs showed the sign-in page; see the reauthentication follow-up above for the later signed-in check. No Gmail mutation or message/attachment access occurred.
- **Local service retry — 2026-11-09 (historical):** On the localhost tab, the UI initially showed the stale vault-service-unavailable notice. Read-only checks through Vite's same-origin proxy returned vault `{initialized:true, unlocked:true}` and Google OAuth configuration `true`. Clicking the page's **Retry** cleared the notice and showed **Continue with Google**. At that time, the app was signed out. This verified the local services were reachable; it did not identify whether the earlier list 401 came from the expired East session or a mailbox token. No Gmail mutation occurred in that check.
- No message body or attachment was opened. No Gmail mutation or send occurred.

## Notes

- Page counts remain estimates returned by Gmail. Search scope and folder/category filters still determine which conversations are counted.
- The active category is part of the scope: in this live check the selected view was Social, not the entire Inbox. The account scope was All accounts.
- The initial body-cache sync limit is unchanged. Older bodies are fetched only when a thread is opened.

## Source-only diagnosis of 660 → 0/0 → 30/402 anomaly — 2026-11-09

No browser, Gmail/API, secrets, tests, or build were used for this diagnosis; application code was not changed.

### Findings

- `totalEstimate` is explicitly only an estimate. `MailReadService.fillSearchMailbox` takes the maximum `resultSizeEstimate` reported for each Gmail mailbox, and `SearchPageCursor.totalEstimate()` sums those values (`backend/src/main/java/com/east/app/mailread/MailReadService.java:276-280,747-749`). It does not cap results or determine whether pagination is over. The next cursor is based on queued results or mailboxes not yet exhausted (`MailReadService.java:213-219`). Therefore loading 660 unique conversations when Gmail estimates 402 is possible and, by itself, does not show a cursor bug. The frontend also follows `nextPageToken`, not the estimate (`frontend/src/components/Conversations.tsx:1281-1291,1908-1910`).
- Backend cursors are in-memory, single-use tokens with a 15-minute expiry; an unknown, expired, wrong-user, or wrong-query token returns `INVALID_PAGE_TOKEN` (`MailReadService.java:61-62,626-645,653-671`). On a successful page, the service stores another token only while a mailbox still has queued results or is not exhausted (`MailReadService.java:205-219`).
- The frontend's stale-token recovery targets the current number of loaded conversations plus one page and replays fresh pages before replacing the visible list (`frontend/src/components/Conversations.tsx:1237-1260`). If that replay fails, it sets an error and leaves the existing `threads` state intact (`Conversations.tsx:1261-1267`); this path alone does not explain a clean 30-row result.
- A visible `0 of 0` transient is consistent with a list-scope transition: the list effect clears `threads` and `totalEstimate` when `queryKey` changes, and then installs the first page and its estimate (`Conversations.tsx:1165-1185`). A first-page response replaces the existing array with that page (`Conversations.tsx:1181-1185`). A same-query refresh triggered only by `refreshVersion` does not clear rows, since `queryChanged` is false (`Conversations.tsx:1167-1175`). A component remount would also return to initial zero state. Thus the observed transition to 30/402 points more toward a query-scope change/remount/refetch than the backend estimate itself. The scratchpad has no request error code, query-key transition, or remount evidence to distinguish these cases.

### UI clarification

- The footer now shows “Loading conversations…” while a new scope is loading with no rows, matching the main list loading state. This avoids presenting the transient cleared state as a real `0 of 0` result. The change does not alter the cursor, query scope, or result estimate.

### Safe recommendation

Do not use `totalEstimate` as an end-of-results condition; the current backend/frontend use the Gmail cursor for that decision. Reproduce before changing cursor logic. During reproduction, capture only non-sensitive diagnostics: whether the component remounted, whether the serialized list query changed, whether `refreshVersion` changed, whether the request was a first page or continuation, and the response error code. Do not log cursor values or message/account content. If the same query is being refetched, keep the previous rows until the replacement first page succeeds and show an updating/loading label rather than a misleading `0 of 0`; if the query actually changed, retain the reset but suppress the zero count while that new scope loads. Investigate stale-cursor recovery separately only if the captured response is `INVALID_PAGE_TOKEN`.

### Reproduction status

Reproduction is needed. Static review explains the approximate-count mismatch and the code path that can briefly clear counts, but does not identify which frontend transition occurred during the live report.

### Runtime refresh — 2026-11-10

- At the user's request, restarted Vite from the current checkout after confirming the Conversations component and related frontend changes are present.
- Vite started on loopback port 5173 with `EAST_API_TARGET=http://127.0.0.1:8081`; the frontend root returned HTTP 200. The backend was left running.
- No build, test, authenticated Conversations read, or Gmail/provider request was performed for this refresh.

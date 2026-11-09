# Conversations UI scratchpad

## Objective and status

Audit the current Phase 2 Conversations UI and its API integration against the product plan, technical plan, and roadmap. **The original audit and a focused follow-up re-review are complete.** This was a read-only source review; no application code, tests, builds, OAuth, Gmail calls, or mailbox actions were run.

## Source checked

- Requirements: `docs/product-plan.md:85-105`, `docs/implementation-roadmap.md:55-68`, and `docs/technical-plan.md:123-146`.
- Frontend: `frontend/src/components/Conversations.tsx`, `frontend/src/components/conversations.css`, `frontend/src/lib/conversations-api.ts`, `frontend/src/lib/mail-actions-api.ts`, `frontend/src/components/Composer.tsx`, `frontend/src/lib/drafts-api.ts`, and the `App.tsx` mode switch.
- Backend contract/source: `backend/src/main/java/com/east/app/mailread/MailReadController.java`, `MailReadService.java`, `MailReadModels.java`, `backend/src/main/java/com/east/app/attachments/AttachmentContentController.java`, and the mail-action/draft controllers.

## Requirement audit

| Area | Current source evidence | Result |
| --- | --- | --- |
| Demo and account distinction | `Conversations.tsx:388-390` keeps the fictional demo separate from real mode. `RealConversations` starts at scope `all` (`:925-945`); the live scope selector shows mailbox names and addresses (`:1517-1524`), and each live row includes a mailbox name/color chip (`:492-559`). `App.tsx:554-615` selects demo or Gmail mode from session state. | Combined All accounts is the real-mode default; per-account selection is available. Account name accompanies color. Demo path remains present. |
| Combined Inbox and categories | `Conversations.tsx:930-937` initializes Inbox/Primary; `:1059-1063` derives available categories from selected mailbox scope; `:1707-1714` renders only available Gmail category tabs. | Matches combined Inbox and per-account category requirements. |
| Search and pagination | `Conversations.tsx:1105-1120` debounces search and omits folder/category when searching while retaining mailbox/read/starred/label filters; `:1200-1220` requests subsequent opaque pages; `conversations-api.ts:372-409` sends list parameters and parses `{items,nextPageToken,totalEstimate}`. Backend `MailReadService.java:197-245,271-339` keeps per-mailbox search queues/cursors and merges candidates. | Full-mailbox search, selected-account scope, filters, and explicit Load more are wired. Current folder/category styling is suspended during committed search. Search ordering risk remains below. |
| Thread reader and responsive layout | `Conversations.tsx:1169-1189` fetches detail by East thread UUID without a read-state mutation; `:813` states opening does not mark read. CSS uses a list/reader split above 720px and hides the list for the reader at `conversations.css:218-248`. | Read-only thread open and desktop/mobile layout match the plan. |
| Labels | `Conversations.tsx:1008-1066` loads mailbox label catalogs; `:1076-1100` aggregates them; `:1620-1703` exposes create/rename/delete and account-qualified label filters; `:835-848` and `:1765-1781` apply/remove a selected user label. `mail-actions-api.ts:123-160,192-215` uses the mailbox label API. | List, filter, single-thread and bulk label operations are present. Combined-scope label names carry their mailbox email. |
| User-confirmed actions and bulk | Row star (`Conversations.tsx:550-559`), reader actions (`:816-849`), and selected-thread bulk actions (`:1722-1768`) invoke actions only from clicks. Bulk selection is capped at 100 (`:420-421`); per-item outcomes and failed selections are retained (`:1315-1385`). API methods use `PATCH /threads/{id}` and `POST /threads/bulk-actions` (`mail-actions-api.ts:163-189`). | Archive, trash, read/unread, spam/not-spam, star, label, and requested bulk operations are wired to explicit clicks. Opening a thread itself does not change Gmail. |
| Attachments | `Conversations.tsx:594-743` fetches bytes only from Preview/Download clicks, previews PDF and common image MIME types, and revokes temporary object URLs. `conversations-api.ts:411-450` requests the raw-byte route; backend `AttachmentContentController.java:21-47` exposes that same route with no-store/nosniff headers. | Preview/download matches the plan for supported incoming attachments; unsupported types have download only. |
| Compose/reply/forward | `Conversations.tsx:1451-1475,907-910,1830-1838` starts new, reply, reply-all, and forward drafts; `Composer.tsx:755-845` separates save-to-Gmail from final send review; `:1162-1185` reviews sender, To/CC/BCC, subject, body, attachments before send. | New message account selection, thread-account replies, explicit Save to Gmail, and message-specific send review are present. |
| Rich text and selected files | `Composer.tsx:885-957` supports user file selection/removal and validates 10 MiB per file, 16 MiB total, and 10 files; `:983-1015,1210-1245` provides bold/italic/underline, links, and lists with sanitized editor input. | Meets the Phase 2 manual composer requirements. Forward attachments are represented by the draft and removable. AI-generated drafting is intentionally Phase 3 per `product-plan.md:103` and `implementation-roadmap.md:70-83`. |

## Focused follow-up re-review

1. **Body HTML and To/Cc/Bcc/Reply-To render safely.** `ThreadMessage` carries those recipient fields and `bodyHtml` (`conversations-api.ts:55-67`); `parseThreadMessage` normalizes recipients and preserves the HTML (`:281-319`). The reader uses native `<details>/<summary>` with a labeled `<dl>` for To/Cc/Bcc/Reply-To (`Conversations.tsx:884-897`), then renders `bodyHtml` (`:916`). The reader's HTML comes from `MailReadService.java:435-436` through `MailHtmlSanitizer.java:11-31`, which applies a Jsoup safelist, limits href protocols to HTTP/HTTPS/mailto, and resets linked anchors to `target="_blank" rel="noopener noreferrer"`. The browser reader relies on that backend sanitizer.

   **There are two `dangerouslySetInnerHTML` sinks in the frontend, not one globally.** The other is Composer's draft review (`Composer.tsx:154-181,314-319`), which passes through `safeEditorHtml` first. That sanitizer rebuilds the allowed markup, restricts links to safe protocols, and fixes `target="_blank" rel="noreferrer noopener"`. Both sinks therefore use sanitized HTML and fixed safe link targets/rel attributes: the thread reader through the backend Jsoup sanitizer, Composer through its local editor sanitizer.

2. **Combined-scope labels and the active-filter summary identify the mailbox.** Sidebar label rows show the owning account email, include it in the title, and filter options include it (`Conversations.tsx:1704-1710,1732-1740`). The catalog retains mailbox IDs (`:1087-1100`). When a label is active, the list summary appends its name and account email in combined scope (`:1269-1278`); with a search query, the summary says “Search results across …” and scopes it to the selected account or the mailbox owning the active label. At narrow sidebar widths the email may be ellipsized visually, while the title and filter option retain the identity.

3. **Search suspends folder/category styling and the summary describes the active query.** A committed nonempty query sets `effectiveCategory` to undefined and excludes folder/category from the request (`Conversations.tsx:1110-1120`). Folder buttons omit active/`aria-current` state while searching (`:1609`); category tabs are hidden (`:1750-1758`). The summary switches to “Search results across …” and includes an active label plus its mailbox identity where relevant (`:1270-1278,1761-1762`). Search is debounced, so styling changes after the query is committed.

4. **Unavailable-body copy and retry behavior are truthful.** The old “open this conversation” instruction is gone. For `ON_DEMAND`, the reader says East could not load the body from Gmail and offers Retry; for a missing cache body it states that the body is unavailable locally and offers Retry; limit states explain the local cache/display limit (`Conversations.tsx:891-900`). Retry increments `detailRetryVersion` and repeats the detail GET (`:1178-1198,1843`), which invokes the backend on-demand path when the body is missing or expired. Limit states do not offer a retry likely to return the same omitted body.

## Remaining limitations and next steps

1. **Search ordering remains best-effort.** `MailReadService.java:271-305` buffers up to a page-sized window per mailbox and `:226-240` globally sorts that buffer by latest message time. This assumes suitable ordering within Gmail's per-mailbox `threads.list` pages. The [Gmail `threads.list` reference](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.threads/list) documents pagination and estimates but no ordering guarantee; the newest-first global order is therefore an inference. Before promising strict newest-first results, either exhaust each mailbox's matching results before global sorting or revise the API/UI contract to avoid guaranteeing order. Add a deterministic test using nonchronological per-mailbox pages when implementation resumes.

2. **Raw headers remain intentionally omitted.** To/Cc/Bcc/Reply-To are shown in the accessible message-details disclosure. `messageId` and the full raw headers map returned by the backend are not modeled in `ThreadMessage`; expose more headers only if a confirmed product workflow requires them.

3. **Maintain the HTML safety boundary.** Continue sanitizing all API-returned email HTML server-side before the client uses `dangerouslySetInnerHTML`; if message detail can later arrive from another source, add client sanitization or an isolated renderer before wiring it.

## Verification and next step

- Static source inspection only; no production build, tests, browser exercise, Gmail calls, or mailbox mutations were performed.
- The four follow-up issues requested for re-review are resolved in current source. The next engineering task is the search ordering contract; no further UI changes are needed for body HTML/recipient rendering, account-qualified labels, search styling, or unavailable-body retry copy.

## Handoff

The follow-up fixes preserve and display body HTML plus To/CC/BCC under server-side sanitization, identify labels by mailbox in combined scope, suspend folder/category highlighting during full-mailbox search, and provide actionable unavailable-body copy. Search pagination still relies on an undocumented per-mailbox Gmail result order. No application code was changed during either audit pass.

## Final thread-reader audit

- Current-source inspection confirms both frontend HTML sinks are sanitized and set fixed safe link attributes. The thread reader's backend Jsoup sanitizer fixes `target="_blank" rel="noopener noreferrer"`; Composer's `safeEditorHtml` fixes `target="_blank" rel="noreferrer noopener"`.
- To/Cc/Bcc/Reply-To appear in a native accessible details disclosure with a summary and definition list.
- Combined labels show account email, and the active list summary includes the label and mailbox identity. Full-mailbox search clears folder/category active styling and uses search-result summary wording; these updates happen once the debounced query is committed.
- Static source inspection only. No application edits, build, tests, API calls, or Gmail actions were performed for this check.

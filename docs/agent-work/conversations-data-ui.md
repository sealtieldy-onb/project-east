# Conversations read-data UI scratchpad

## Objective and status

Add an opt-in read-only Conversations mode backed by the mailbox/thread GET endpoints while preserving the existing fictional demo by default. Implementation is complete. The production build passed after the final Conversations and retention-range changes.

## Component contract

- `Conversations` accepts `realMailboxMode?: boolean`, defaulting to `false`.
- With `false`, the existing demo implementation is rendered unchanged.
- With `true`, the component loads only `/api/v1/mailboxes`, `/api/v1/threads`, and `/api/v1/threads/{threadId}`. It does not fall back to fictional content when a request fails or no mailboxes exist.
- Account, folder, category, search, unread/read/starred, label, and opaque page-token state are sent as list query parameters. Search omits folder/category so it covers the selected mailbox's full mailbox.
- Opening a thread loads its detail but never calls a mailbox mutation. Action controls stay disabled.

## API shape assumptions

`docs/technical-plan.md` defines the response content but does not specify all JSON names or query parameter names. The client currently assumes:

- `GET /api/v1/mailboxes` returns a JSON array or `{ items: [...] }` / `{ mailboxes: [...] }`. Each mailbox has `id`, `email`, optional `displayName`, optional safe hex `color` and/or `colorIndex`, optional `syncState`, `lastSyncAt`, and categories under `categories`, `categoryTabs`, or `availableCategories`.
- `GET /api/v1/threads` accepts `mailboxId`, `folder`, `category`, `query`, `unread`, `starred`, `labelId`, `pageToken`, and `pageSize`. The opaque token is passed through unchanged as a query parameter. Search uses `query` and drops `folder` and `category` to search the full selected mailbox.
- The thread page is `{ items, nextPageToken, totalEstimate }`. Each item includes `id` (or `threadId`), `mailboxId`, `subject`, nullable `snippet`, `sender` (string or `{ name, email }`), `latestMessageAt` (or `latestMessageTime`), booleans `unread` and `starred`, `labels`, `category`, `messageCount`, and attachment availability (`attachmentsAvailable` or `hasAttachments`). Mailbox/account identity may be nested as `mailbox` or `account`; otherwise it is resolved from the mailbox list.
- Thread `labels` may be strings or objects `{ id, name, color? }`. Label filtering sends `labelId` using the server label ID. Available label options are those observed in loaded pages because this work is restricted to the three read routes.
- Thread detail is a direct JSON object with `id`/`threadId`, `mailboxId`, subject, labels, and ordered `messages`. Each message has `id`/`messageId`, `sender`/`from`, `timestamp`/`sentAt`/`date`, optional `bodyText`/`body`, optional `to`/`recipients`, and optional attachment metadata. Attachments may also be on the thread. HTML bodies are deliberately not rendered; plain text is rendered as escaped React text.
- Folder values are Gmail-style uppercase names (`INBOX`, `STARRED`, `SNOOZED`, `SENT`, `DRAFTS`, `ALL_MAIL`, `SPAM`, `TRASH`); category values are `PRIMARY`, `SOCIAL`, `PROMOTIONS`, `UPDATES`, and `FORUMS` (Gmail `CATEGORY_*` aliases are normalized).

Align the controller/query names with these assumptions before integration; no mailbox bodies or account data are stored in browser storage or written to logs.

## Files

- `frontend/src/components/Conversations.tsx`
- `frontend/src/components/conversations.css`
- `frontend/src/lib/conversations-api.ts`
- `docs/agent-work/conversations-data-ui.md`

## Verification

- `npm run build` in `frontend/` — passed (`tsc -b` and Vite; 2,070 modules transformed, exit code 0).
- No live backend/API runtime check has been run.

## Handoff

Parent can opt into the read-only implementation with `<Conversations realMailboxMode />`. Mailbox actions and composition remain disabled pending the separate mutation/composer workstream.

## Follow-up — Inbox category scope

- Category tabs and the category query parameter now apply only while the selected folder is `INBOX`.
- Switching folders clears the selected category; returning to Inbox restores the default `PRIMARY` selection. Search still clears the folder/category filter as before, so on-demand search remains full-mailbox search.
- Verification after this change: `npm run build` in `frontend/` passed; no tests or Gmail/API calls were run.

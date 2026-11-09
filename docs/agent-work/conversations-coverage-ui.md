# Conversations message coverage audit

Date: 2026-11-09
Status: Read-only UI audit; no application files or Gmail data changed.

## Findings

- The live Conversations view defaults to `INBOX`, `PRIMARY`, and All accounts. `RealConversations` serializes that scope into `getThreadPage` with `pageSize: 30`.
- The list shows a Load more control only while the backend returns `nextPageToken`; otherwise it says end of current results. This can paginate the result set the backend exposes, but it cannot extend that set by itself.
- The refresh icon only increments the list refresh version and repeats `getThreadPage`. It does not trigger Gmail sync. Manual sync is exposed elsewhere in the app.
- Search is a separate on-demand path. Product docs say it can search each connected Gmail mailbox beyond the initial sync baseline; ordinary folder/category listing is expected to be local cached pagination.
- Consequently, a mailbox with many messages can currently show only the initial sync cache in Inbox/category views. Search may find older messages when given a query, but it does not make the ordinary Inbox listing complete.
- Product-plan gap: the bounded 30-message sync is intentional for initial sync, but the user expects Gmail-style full-folder browsing. The current UI does not disclose that folder views are limited to cached metadata.

## Recommendation

Add a Gmail-backed, paginated folder/category listing on demand, using Gmail thread-list queries with account/folder/category filters and bounded page sizes. Preserve the 30-message initial sync/body-cache policy; hydrate full message bodies only when a thread is opened, and continue to require user clicks for every Gmail mutation. Make the folder count/current coverage and loading state clear. Do not rely on the current Load more button unless the backend can provide a remote next page for uncached results.

## Verification performed

- Inspected `frontend/src/components/Conversations.tsx`, `frontend/src/lib/conversations-api.ts`, `docs/product-plan.md`, and `docs/implementation-roadmap.md`.
- No live Gmail/API calls, mailbox actions, messages, or attachments were accessed.

## Resolution — 2026-11-09

The folder/category path was changed to use Gmail metadata pagination. Live UI now shows 30 of about 402 combined Inbox/Primary conversations, then 60 after Load more. Both account identities appear in the result list. The frontend now identifies on-demand body loading and describes the total as approximate. No message body or attachment was opened for this verification.

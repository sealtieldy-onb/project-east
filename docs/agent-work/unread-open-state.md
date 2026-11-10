# Unread state on open

## Scope

Implement the requested Gmail-style unread row appearance and mark unread conversations read when the user opens them. Update the notice to distinguish opening a thread from loading external images.

## Source findings

- Connected Conversations previously selected a thread without changing its read state; the toolbar and footer said opening never marked it read.
- The existing `PATCH /api/v1/threads/{threadId}` action supports `MARK_READ`, and the backend resolves the thread through the authenticated app-user ownership check. No backend or schema change was needed.
- Thread body and inline image loading are separate from the read-state action; external images still require their own user action.

## Changes

- Queue an owner-scoped `MARK_READ` request on explicit row open and Dashboard report-source navigation, matching by both thread and mailbox when available. Pending open requests wait for the matching list/detail metadata.
- Deduplicate effect processing with a monotonically increasing request ID. Already-read threads make no request. Read/unread actions update the current list and reader state immediately, then refresh Gmail metadata. Failures use the existing sanitized API error message and leave the unread state visible for retry.
- Make unread rows more distinct with a Nord-tinted surface, stronger sender/subject weight, and a marker; accessible row names include “Unread.”
- Update the reader notices and product/technical/roadmap docs. The decision register records that opening is the explicit read action and external-image consent remains separate.

## Verification

- `git diff --check` passed.
- No tests, build, browser/runtime check, Gmail request, or provider request was run.

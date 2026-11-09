# Phase 2 bulk mailbox-action matrix tests

Date: 2026-11-09

## Scope

Added a separate synthetic contract test for the bulk `MailActionsService` route. The test uses the production Gmail client with an in-memory request factory and two fictional mailbox/thread/token fixtures; no external network, Google account, configured database, browser, or real message data is used.

## Cases

- `supportedBulkActionsRouteToEachMailboxAndSynchronizeOnlyAfterSuccess` checks Archive, Move to Inbox, Trash, Restore from Trash, Read/Unread, and Spam/Not spam across one thread from each of two mailboxes. It asserts each thread receives its owner's token, the correct Gmail request body/path, per-thread successful outcome, and local state synchronization after the request.
- `bulkLabelApplyAndRemoveUseEachMailboxesOwnLabelIds` models the frontend's mailbox-grouped requests and checks apply/remove use the distinct existing user-label ID belonging to each mailbox.
- `oneMailboxFailureDoesNotPreventOtherBulkItemsFromCompleting` returns a synthetic 403 for the first mailbox and success for the second; it verifies per-item outcomes, exact token routing, and that only the successful thread's local state is synchronized.

## Verification

- Maven held for the parent-run combined suite to avoid concurrent writes under `backend/target`.
- `git diff --check` pending.

## Limits

Coverage verifies East's outbound bulk request mapping and per-item local persistence order with synthetic HTTP responses. It does not demonstrate Gmail provider side effects or replace deferred live acceptance checks.

# Phase 2 mailbox-action matrix tests

Date: 2026-11-09

## Scope

Added isolated synthetic contract coverage for `MailActionsService` and the production `GmailApiClient` using a fake Gmail host and an in-memory `ClientHttpRequestFactory`. No Google request, configured database, account data, token, credential, browser, or production code was accessed or changed.

## Cases

- `supportedThreadActionsBuildExpectedMailboxScopedGmailRequests` checks the method, route, mailbox bearer token, Gmail label body, and local thread update for Archive, Move to Inbox, Trash, Restore from Trash, Read, Unread, Spam, Not spam, Star, Unstar, Add label, and Remove label. It verifies every request resolves the owner/thread mailbox token and that local state is written only after the fake Gmail response succeeds.
- `failedGmailActionDoesNotUpdateLocalMailboxState` returns a synthetic Gmail 403 for Trash and verifies the error is surfaced while local thread state remains untouched.

## Verification

- Maven was held for parent-run combined verification to avoid concurrent writes in `backend/target`.
- `git diff --check` is pending parent verification.

## Limits

These tests verify East's outbound request mapping and post-success local synchronization contract with synthetic responses. They do not prove Gmail provider effects or replace deferred live acceptance checks.

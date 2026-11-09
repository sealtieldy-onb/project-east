# Phase 2 mailbox-action outbound routing test

Date: 2026-11-09

## Scope and method

Added an isolated integration-style unit test for the real `MailActionsService` and final `GmailApiClient`. It configures the client's `RestClient.Builder` through a test-only proxy to retain a fake `gmail.test` base URL, and provides an in-memory `ClientHttpRequestFactory` that records the outbound request and returns a synthetic JSON response. The test cannot contact Google. No production code, migrations, existing tests, secrets, browser state, or Gmail data were changed or accessed.

## Case

- A combined bulk `MARK_READ` action contains one thread for each of two mailboxes. The test checks separate owner-scoped token lookups; captures two `POST` requests to the corresponding Gmail thread IDs; verifies each request's bearer header matches that thread's mailbox token, and checks the `UNREAD` removal body; then verifies local synchronization is recorded against each original thread/mailbox pair (`backend/src/test/java/com/east/app/mailactions/MailActionsServiceOutboundRoutingTest.java:39-84`).

## Verification

- Targeted Maven command: `cd backend && mvn -o -B -Dtest=MailActionsServiceOutboundRoutingTest test`.
- Status: pending parent-run combined verification. Per parent instruction, this workstream did not run Maven to avoid concurrent writes in `backend/target`.

## Limits

This uses a fully in-memory HTTP transport and synthetic mailbox/thread/token data. It verifies actual request construction and account-token routing in East's client/service path, but it does not contact Gmail or assert provider-side effects.

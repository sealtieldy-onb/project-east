# Phase 2 mailbox-action ownership tests

Date: 2026-11-09

## Scope and method

Added isolated unit coverage for `MailActionsService` ownership and mailbox-scoped token resolution. No production code, migrations, existing tests, secrets, browser state, or Gmail data were changed or accessed. Synthetic IDs and an invalid example email were used throughout.

## Cases

- A thread owned by another app user is treated as not found; no access-token lookup or local mailbox-state synchronization occurs (`backend/src/test/java/com/east/app/mailactions/MailActionsServiceOwnershipTest.java:31-52`).
- A label that is unavailable in the selected thread's mailbox is rejected before token lookup; the service passes the thread mailbox ID to the mailbox-scoped repository lookup (`:54-80`).
- A combined bulk action resolves tokens separately for each thread's owning mailbox. When those lookups return no token, both outcomes are safe failures and no local sync or Gmail request occurs (`:82-110`).
- Label creation for a mailbox not owned by the current user is rejected before token lookup (`:112-129`).

## Verification

- First targeted run failed during test compilation because the fake repository override reduced the visibility of `synchronizeThreadState`. The test override was corrected to `public`.
- `cd backend && mvn -o -B -Dtest=MailActionsServiceOwnershipTest test` — passed; 4 tests, 0 failures, 0 errors, 0 skipped.

## Limits

The tests intentionally never call Gmail. They prove preflight ownership rejection and per-thread mailbox selection for token lookup, but do not verify the outbound Gmail REST request or the returned access-token value. `GmailApiClient` is final and the test dependency set has no mocking/HTTP-server library; end-to-end request routing remains covered by existing code review and live user-controlled acceptance.

# Phase 2 draft send-safety tests

Date: 2026-11-09

## Scope and method

Added isolated unit coverage for `DraftService.send` preconditions using in-memory repository/vault doubles. The tests call no Gmail endpoint and modify no mailbox or database state. Existing `DraftPersistenceEncryptionTest.sendWithoutExplicitConfirmationStopsBeforeRepositoryOrGmailAccess` already covers a false confirmation; these additions cover stale review timestamps and a durable `SEND_PENDING` state.

## Cases

- `staleReviewTimestampStopsSendBeforeMailboxOrGmailAccess` — a confirmed send using an old `expectedUpdatedAt` returns HTTP 409 before access-token retrieval, mailbox email lookup, or `markSideEffectPending`.
- `sendPendingDraftCannotBeRetriedAndReportsReconciliationState` — another send attempt against `SEND_PENDING` returns HTTP 409 before token/mailbox/Gmail work; `reconciliationStatus` reports required reconciliation, the `SEND` operation, the prior `LOCAL` state, and its start time.

## Verification

- `mvn -o -B -f backend/pom.xml -Dtest=DraftServiceSendSafetyTest test` — passed, 2 tests, 0 failures/errors/skips.
- The first targeted invocation hit a concurrent compile error in `MailActionsServiceOwnershipTest` (`synchronizeThreadState` override visibility). The owning workstream corrected that method to `public`; rerunning this targeted command compiled 17 test sources and passed.
- No production files or existing tests were edited by this workstream. Full-suite results are tracked separately.

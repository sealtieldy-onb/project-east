# Phase 2 draft reconciliation tests

Date: 2026-11-09

## Scope and method

Inspected `DraftService.reconcile`, `reconciliationStatus`, `DraftReconciliationRequest`, and `DraftRepository` pending-state transitions. Added synthetic in-memory tests for uncertain **local send** outcomes only. Both outcomes use a pending row with no Gmail draft ID; the production path therefore needs no Gmail lookup. The Gmail API client is null in the fixture, and access-token/mailbox calls are counted and asserted at zero.

## Cases

- `unknownLocalSendOutcomeCanBeResolvedOnlyAfterConfirmationAndNoGmailCall` — false confirmation returns HTTP 400 without reading the draft; after an explicit completed-send confirmation, the pending row is deleted and East returns `SENT`.
- `explicitlyUnsentLocalOperationRestoresTheDraftWithoutGmailAccess` — explicit not-completed confirmation restores `SEND_PENDING` to `LOCAL`; the status endpoint then reports no reconciliation required.

## Verification

- The parent asked me to hold Maven execution while sibling workstreams finish, to avoid concurrent writes under `backend/target`. The focused command to run afterward is `mvn -o -B -f backend/pom.xml -Dtest=DraftServiceReconciliationTest test`.
- No production files, live Gmail data, configured database, secrets, or existing test files were changed by this workstream. `git diff --check` is the only check run here; parent will perform the combined Maven verification.

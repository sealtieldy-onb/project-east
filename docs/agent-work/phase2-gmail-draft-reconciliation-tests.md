# Phase 2 Gmail-backed draft reconciliation tests

Date: 2026-11-09
Status: all four synthetic reconciliation tests passed in the parent full Maven run on 2026-11-09.

## Scope

Added synthetic service-level tests for Gmail-backed uncertain draft operations. They use an in-memory HTTP request factory with a fake `gmail.test` base URL, a temporary local vault key, synthetic message data, and in-memory draft/token fakes. They cannot contact Google or use the configured database.

## Cases

- `confirmedCompletedSaveRequiresMatchingGmailContentThenCommitsSavedState`: a pending local save is finalized only after the explicitly identified Gmail draft's sender, recipients, subject, and body match the encrypted local payload; local encrypted content is then cleared.
- `mismatchedGmailContentLeavesUncertainSavePending`: a Gmail draft with different content is rejected and the pending state and local ciphertext remain intact.
- `confirmedNotCompletedSavedSendChecksGmailDraftBeforeRestoringIt`: a saved draft is fetched and validated before an uncertain send is restored to `SAVED_TO_GMAIL`; the test asserts the send is not repeated.
- `confirmedCompletedSavedSendDeletesPendingRecordWithoutRetryingGmailSend`: after the user confirms they checked Gmail and the send completed, East clears the pending saved-send record without calling Gmail again or retrying the send.

## Verification

- `git diff --check` passed for the added reconciliation case; Maven was not run by this workstream.
- The parent's combined Maven test compile found Java visibility errors in the in-memory vault fake: its overrides of public `VaultRepository.find`, `isInitialized`, and `create` were package-private. These overrides are now public. Per parent instruction, I did not rerun Maven; the parent will verify the corrected test in the combined run.
- Suggested focused command: `mvn -o -B -f backend/pom.xml -Dtest=DraftServiceGmailReconciliationTest,DraftServiceReconciliationTest test`.

## Limits

All cases are synthetic and the Gmail client transport is in memory. They cover matching/mismatching save reconciliation, saved-send not-completed verification, and the confirmed-completed attestation path. A confirmed-completed outcome deliberately trusts the user's separate Gmail check and does not attempt to infer delivery from Gmail or retry `drafts.send`. The tests do not cover Gmail's real eventual consistency, attachment-bearing drafts, provider 404/401 recovery, or runtime UI interaction.

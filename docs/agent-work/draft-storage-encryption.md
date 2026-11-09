# Draft storage encryption tests

## Objective

Verify the Phase 2 persistence boundary for local draft payloads and staged user-selected attachment bytes without contacting Gmail or the configured database.

## Implementation

- Added `DraftPersistenceEncryptionTest` with a capturing in-memory `DraftRepository`, temporary owner-only vault key, and synthetic draft/file data.
- The local-draft case invokes `DraftService.create`, observes the repository arguments, confirms plaintext subject/body are absent from ciphertext, decrypts the record with its user/draft/mailbox AAD, and confirms a changed user, draft, or mailbox ID cannot decrypt it.
- The attachment case invokes `DraftService.addAttachment`, confirms captured bytes are ciphertext, decrypts only with user/draft/attachment AAD, and confirms changing any bound ID is rejected.
- The send-guard case submits `confirmed: false` and verifies the request stops with HTTP 400 before any draft-repository access; the Gmail client is absent from the fixture.
- Temporary captured buffers and synthetic attachment data are wiped in test cleanup. No configured PostgreSQL data is read or changed, and no Gmail route is called.

## Verification

- `mvn -o -B -f backend/pom.xml test` passed: 10 tests, 0 failures, 0 errors, 0 skipped.
- The test suite includes three vault/key-file tests, one encrypted AI-key persistence test, one OAuth-token persistence test, three draft-storage/confirmation tests, and two retention-cleanup tests.

## Limits / remaining acceptance

- These tests prove only the local in-memory repository boundary and AEAD behavior. They do not replace PostgreSQL persistence/retention checks or live draft save/read-back/delete/send acceptance.
- The user previously deferred attachment runtime checks. Do not load real attachments or create/save/send a live draft without the user present and acting on the explicit Gmail controls.

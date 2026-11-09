# Phase 2 attachment content service tests

Date: 2026-11-09

## Scope and method

Added one isolated test class for `AttachmentContentService`; no production code, existing tests, migrations, secrets, browser state, or Gmail data were changed or accessed. Fixtures use synthetic UUIDs, Gmail identifiers, bytes, a fixed clock, a fake repository, and stubbed local vault/token services. The Gmail client is constructed but never called in these tests.

## Coverage

- `attachmentNotOwnedByCurrentUserIsRejectedBeforeTokenOrGmailAccess` verifies a missing owner-scoped attachment becomes a not-found response before vault decryption, token lookup, or Gmail access.
- `cachedAttachmentIsServedOnlyWhileBothCacheAndSourceBodyAreLive` verifies live cache bytes are decrypted and returned with the stored safe filename and PDF type without token refresh.
- `expiredCacheIsClearedBeforeARefreshCanReplaceIt` verifies an expired attachment cache is cleared before any token refresh and its old bytes are never decrypted or returned.
- `cachedBytesFetchedBeforeExpiredSourceBodyAreNotServed` verifies an attachment fetched before its source-body expiry is cleared and not served even when its own TTL has not elapsed.

## Verification

Ran only the targeted class:

```sh
mvn -o -B -Dtest=AttachmentContentServiceTest test
```

Result: 4 tests run, 0 failures, 0 errors, 0 skipped; Maven reported `BUILD SUCCESS`. No full backend suite was run as part of this workstream.

## Limits

The tests use a fake repository and therefore do not execute the ownership SQL, transactional cache cleanup, or PostgreSQL AAD/cache persistence. Live attachment preview/download checks remain deferred by the user. These tests do not claim the Gmail API client or browser rendering path works end to end.

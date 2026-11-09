# Phase 2 reply and forward source tests

Date: 2026-11-09

## Scope

Added `DraftSourceCompositionTest` using only synthetic owner, mailbox, thread, message, recipient, and body-cache values. The fake `MailReadRepository` supplies an encrypted cache made with the production `GmailCacheAad`; the test exercises the actual `DraftSourceService.load` and `DraftService.create` paths. Null Gmail/token collaborators ensure the tested cases do not make Gmail calls. No production code, migrations, real mailbox data, secrets, or existing tests were changed.

## Coverage added

- Reply uses a source `Reply-To` address by default, quotes the synthetic source body, prefixes the subject, and retains the Gmail thread ID, `In-Reply-To`, and merged `References` in the encrypted local payload.
- Reply all defaults To to `Reply-To`, defaults CC to original To/Cc recipients, excludes the local sending account and the selected To recipient, and removes exact duplicate addresses.
- Forward quotes the source message, retains an explicit recipient and forwarded subject prefix, and does not attach Gmail reply-thread headers.
- Reply source ownership and mailbox matching are checked before message content is loaded or a local draft is inserted.

## Verification

- `git diff --check` is the only check run by this workstream; it passed.
- Maven is held for parent combined verification. Suggested focused command: `mvn -o -B -f backend/pom.xml -Dtest=DraftSourceCompositionTest test`.

## Limits

The synthetic forward case has no attachment. It does not exercise Gmail attachment retrieval/staging, inline attachments, UI composer behavior, Gmail Drafts save/send, or the user-deferred live Reply/Forward/attachment checks. No live Gmail data or mailbox state was touched.

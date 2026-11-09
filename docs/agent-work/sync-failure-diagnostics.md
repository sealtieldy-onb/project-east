# Gmail sync failure diagnostics workstream

## Objective

Identify and resolve the first connected mailbox's generic initial-sync failure without exposing email content, OAuth tokens, raw Google responses, or exception text to the browser.

## Findings

- Manual sync previously returned only `FAILED`, so the Dashboard could not distinguish Gmail API setup, authorization, quota, or transient failures.
- `GmailApiClient` already bounded and sanitized Google's error identifier, but parsed the broad `error.status` before the more useful `errors[0].reason`.
- Token refresh failures were caught as generic runtime failures inside `GmailSyncService`.
- The existing Gmail sync warning logs only a sanitized error category and mailbox identifier; no content is logged.

## Changes

- Preserve the sanitized Google reason and HTTP status in a constrained `failureCode` field on the manual-sync response.
- Map auth failures to a status-only category. For non-Gmail runtime failures, include only the bounded root exception simple class name in a `sync_exception_<Type>` category; never include messages, stacks, raw response text, or email content.
- Validate the API category against a fixed safe pattern before serializing it.
- Prefer Google's sanitized specific `reason` over the generic status.
- Render fixed user guidance in the frontend and include only the sanitized Google reason where helpful; never show raw response messages or bodies.
- The source-location retry identified `IllegalStateException_GmailSyncService_parseSentAt_649`. Gmail returns `internalDate` as a decimal string; the parser previously accepted only a JSON number and otherwise relied on the `Date` header.
- Updated `parseSentAt` to parse either a numeric JSON value or a textual epoch-millisecond value, falling back to the RFC 1123 `Date` header for malformed/missing internal timestamps. Kept the exception message out of the diagnostic path.

## Verification

- `mvn -o -B -f backend/pom.xml -Dmaven.repo.local=/private/tmp/east-m2 compile` — passed, 113 Java sources.
- `npm run build` — passed, 2,078 modules transformed.
- After the timestamp and key-file edits, offline Maven compilation passed for 113 sources and `npm run build` passed for 2,078 modules. The backend restarted on 8081, Liquibase reported 001–005 current, and the Dashboard now reports the connected mailbox sync is up to date.
- No tests were added or run. No mailbox mutation or email send occurred.

## Runtime follow-up — 2026-11-09

After the destructive local-data reset, the user continued Google sign-in and connected one mailbox. Its read-only sync is current. Full-mailbox search loads 30 of approximately 201 conversations; the next page increases the loaded count to 60. The search timeout is now 90 seconds and the estimate is monotonic while pages load. No Gmail mutations, attachment openings/downloads, draft saves, or sends were performed. Phase 2 acceptance still needs a second mailbox and user participation in the user-click workflows. East continues to use the automatically loaded owner-only key file with no routine passphrase prompt or idle lock.

## Next step

The read-only sync and search-pagination checks are complete for one mailbox. Continue Phase 2 acceptance with a second account and, with the user present, the explicit-click mailbox, attachment, draft-save, and send workflows. Keep every Gmail mutation and send behind a user action.

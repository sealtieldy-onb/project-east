# Mailbox color allocation

## Objective

Give every connected mailbox a stable, distinct Nord-compatible accent without wrapping a short palette or imposing an arbitrary small account limit.

## Status

Implemented and parent-integrated. The workstream itself did not run tests, builds, runtime checks, database migrations, OAuth flows, or Gmail requests. Parent verification later passed backend compilation and applied migration 004 to both the configured database and a clean temporary database. The migration include is present in `db.changelog-master.xml`.

## Design and changes

- Preserve the first eight existing Nord swatches, then derive an accent deterministically from the persisted account color index with a golden-angle HSL sequence. No modulo palette reuse is used.
- Allocate the first unused nonnegative Java integer for an app user, starting from a deterministic hash of the Google subject. The existing app-user row lock serializes concurrent mailbox allocation. A reconnect retains the mailbox's stored index.
- Widen PostgreSQL `connected_mailbox.account_color_index` from `SMALLINT` to `INTEGER`, remove the original `0..7` constraint, and retain a nonnegative constraint in `004-account-color-capacity.sql`.
- Return a clear HTTP 409 allocation error if the entire representable index space is exhausted; never silently assign an occupied index.
- Share frontend index validation and color generation through `frontend/src/lib/mailbox-colors.ts`. Apply generated indices to connected mailbox colors in the app shell, connected Dashboard, and Conversations UI.

## Files

- `backend/src/main/java/com/east/app/auth/MailboxRepository.java`
- `backend/src/main/java/com/east/app/auth/MailboxService.java`
- `backend/src/main/resources/db/changelog/004-account-color-capacity.sql`
- `frontend/src/lib/mailbox-colors.ts`
- `frontend/src/lib/auth-api.ts`
- `frontend/src/lib/conversations-api.ts`
- `frontend/src/App.tsx`
- `frontend/src/components/ConnectedDashboard.tsx`
- `frontend/src/components/Conversations.tsx`

## Handoff

Parent integration follow-up: migration 004 was added before 005 and successfully applied against both the existing database and a clean database. The generated constraint name matched the initial schema; no color indices were modified by the migration.

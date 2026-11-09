# Liquibase schema review scratchpad

## Objective

Review Liquibase schema/reset behavior and PostgreSQL Docker Compose/property compatibility, then address actionable findings without modifying the original migration.

## Finding and fix

- The `mail_message_label_label_scope` foreign key cascades on `(label_id, mailbox_id)`, but the table's primary key starts with `message_id`. PostgreSQL does not automatically index the referencing side of a foreign key, so label deletion can scan message-label links.
- Added `002-mail-message-label-cascade-index.sql` with `mail_message_label_label_mailbox_idx` over `(label_id, mailbox_id)` and included it as Liquibase changeset `002-mail-message-label-cascade-index`, author `east`, in the master changelog.
- Kept `001-initial-local-schema.sql` unchanged.

## Verification

- Source review confirms the new index matches the cascading FK's column order and the new changeset follows the existing ID/author convention.
- No tests, builds, database migrations, or live calls were run. `.env` was not accessed.

## Files

- `backend/src/main/resources/db/changelog/002-mail-message-label-cascade-index.sql`
- `backend/src/main/resources/db/changelog/db.changelog-master.xml`
- `docs/agent-work/schema-review.md`
- `docs/project-progress.md`

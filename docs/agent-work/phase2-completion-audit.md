# Phase 2 completion audit

Date: 2026-11-09
Status: Phase 2 remains active. This is an evidence map, not a completion claim.

## Verified in the current workspace

- **Local stack and binding:** Vite and Spring Boot are listening on IPv4 loopback ports 5173 and 8081. `docker compose config --quiet` passed; `docker compose ps` reported PostgreSQL healthy. The Spring configuration forces loopback binding. See `docs/run-local.md`, `compose.yaml`, and `backend/src/main/java/com/east/app/config/LoopbackServerConfiguration.java`.
- **Build and schema:** `npm run build` passed (2,078 modules); `mvn -o -B -f backend/pom.xml -DskipTests compile` passed; Liquibase changesets 001–007 are present in the configured PostgreSQL database and all seven were previously applied to an empty temporary database. The current database migration IDs were read-only queried on 2026-11-09.
- **Local credential hygiene:** root `.env` and `~/.east/vault.key` both have POSIX mode `0600`; `.env` is ignored and no Gmail-address pattern was found in `frontend/src` or `backend/src`. Do not read or print `.env` or key-file values.
- **Persisted multi-account state:** a read-only aggregate query confirms one app user, two connected mailbox rows, both with `last_sync_at`, two distinct account-color indexes, and 413 cached `mail_thread` metadata rows. No email addresses, subjects, snippets, or message bodies were queried.
- **Conversation paging:** bounded 30-thread metadata pages, scroll sentinel, manual Load more, cursor recovery, all-account and per-account scope, and category selection are implemented. In the latest signed-in read-only check, each account-specific Inbox · Primary view showed 30 of about 201; the combined view advanced to 90 of about 402; and All mail · All categories showed 30 of about 402. These are Gmail estimates scoped to the selected folder/category. See `docs/agent-work/conversations-scroll-pagination.md` and `docs/agent-work/conversations-full-folder-listing.md`.
- **Mail actions and drafts:** source audits record ownership checks and explicit UI action controls. Star/Unstar was verified live and restored. Synthetic backend tests cover encryption and send confirmation/review binding. See `docs/agent-work/phase2-mail-actions-acceptance.md` and `docs/agent-work/phase2-drafts-acceptance.md`.
- **Retention and report provenance:** source audit, migration 007, and offline compilation cover encrypted local key storage, sensitive-field encryption, body-cache expiry and cleanup, mailbox disconnect cleanup, and Phase 3 source-message provenance. See `docs/agent-work/phase2-retention-acceptance.md`.
- **Passphrase decision:** the original passphrase/15-minute idle-lock request was later explicitly replaced by the user's request to remove routine passphrase locking for this localhost-only app. Follow `AGENTS.md` and current product decisions: load the owner-only local key file automatically; retain the warned destructive reset and one-time legacy migration flow.
- **Phase 3 boundary:** AI summaries/insights remain out of Phase 2. No AI provider was called or configured as part of this work.

## Still required before completion

- **Authentication follow-up:** this check completed without a sign-in prompt or 401. The user asked to be notified in this thread whenever fresh Google sign-in is needed; defer authenticated mailbox work until they complete it.
- **Conversation browsing:** combined and per-account Inbox · Primary, plus All mail · All categories, were verified read-only. The current estimate happened to be about 402 in the combined Inbox and All mail views; each view still has its own Gmail estimate and folder/category scope.
- **User-click Gmail actions:** only Star/Unstar has live round-trip evidence. Archive, Trash, read/unread, spam/not-spam, user-label CRUD/application, and bulk outcomes remain unverified. Each external change must be initiated by the user's own click and restored where practical.
- **Draft runtime workflows:** new/reply/reply-all/forward composition, Gmail Drafts save/read-back/delete, explicit reviewed send, and uncertain-operation reconciliation remain unverified. The user must operate every Gmail mutation and send control.
- **Attachments:** preview/download and outgoing-file runtime checks are explicitly deferred by the user. Do not perform them unless the user later reauthorizes these checks.
- **Database expiry runtime:** source and synthetic scheduling/SQL-intent tests exist, but PostgreSQL-backed expiry behavior has not been exercised end to end.

## Current-turn verification

- `npm run build` — passed; Vite transformed 2,078 modules.
- `mvn -o -B -f backend/pom.xml test` — passed; 12 tests, 0 failures/errors/skips.
- `docker compose config --quiet` — passed.
- The shell could not query `docker compose ps` because its Docker socket access was denied. Earlier Docker Compose status confirmed PostgreSQL healthy on `127.0.0.1:15432`; the live signed-in Conversations page also returned backend-backed Gmail metadata during this turn.
- Earlier read-only PostgreSQL query confirmed Liquibase IDs `001-initial-local-schema` through `007-summary-run-source-provenance` are applied. Earlier aggregate query showed one app user, two synced mailboxes, two distinct color indexes, and 413 thread metadata rows; the owner-only key file mode was `0600`.
- Live read-only list checks showed 30 of about 201 Inbox · Primary conversations per account, 30 of about 402 in All mail · All categories, and advanced the combined Inbox from 30 to 90 of about 402. No thread was opened, no attachment was accessed, and no Gmail mutation or send occurred.

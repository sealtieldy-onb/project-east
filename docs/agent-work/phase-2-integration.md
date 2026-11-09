# Phase 2 integration scratchpad

## Later parent verification — 2026-11-09

This scratchpad preserves earlier implementation checkpoints below. The current state is in `docs/project-progress.md` and `docs/agent-work/phase2-completion-audit.md`: the configured and disposable databases have applied Liquibase 001–008, the full backend suite passes 27 tests, and the frontend production build passes. The user confirmed two-account sync/listing plus Star/Unstar, Read/Unread, user-label lifecycle, Spam/Not spam, and Gmail draft create/save/readback/send/trash. Attachments, Archive → Move to Inbox, Trash → Restore, and Reply are deferred; Reply all/Forward, bulk outcomes, auth-failure runtime behavior, and database-backed expiry still need evidence. Do not repeat deferred checks or send mail on the user's behalf.

## Current source audit repair — 2026-11-09

- **Objective:** Fix the metadata-only sync expiry/schema mismatch while retaining encrypted snippets for the configured TTL; reduce unnecessary inline attachment data exposure in reply fallback reads; and map oversized attachment responses safely. Keep Gmail operations user-triggered; no live mailbox actions.
- **Status:** Source repairs and local migration verification complete. User-triggered Gmail acceptance remains pending.
- **Plan:** Split body-marker expiry from snippet expiry; permit equal timestamps only for `ON_DEMAND` and `SYNC_LIMIT_ON_DEMAND`; extract attachments only for forwards and clear MIME `body.data` on all exits; fall back to bounded metadata on oversized source reads; map size errors and sanitize filenames. Build, apply changesets, and record current runtime.
- **Files:** `DraftSourceService.java`, `DraftAttachmentPolicy.java`, `AttachmentContentService.java`, `MailHtmlSanitizer.java`, `GmailSyncService.java`, `GmailSyncRepository.java`, `MailReadService.java`, `006-immediate-body-expiry.sql`, Liquibase master, and Conversations frontend/API files.
- **Verification:** Maven compiled all 113 Java sources; Vite production build passed with 2,078 modules; Compose validation and Liquibase XML parsing passed. Changeset 006 applied on the configured database, and a clean temporary database applied all six changesets before being dropped. The temporary app could not bind port 8082 after migrations because that port was occupied. The current backend is running on 8081; its restart cleared the in-memory login session. No tests, post-migration full-mailbox search, message openings, attachment interactions, mutations, drafts, or sends were performed. The Conversations list did load through the corrected proxy with two account-scope options and eight combined rows.
- **Handoff:** Two mailboxes are connected and appear in the Conversations account scope; await user participation for explicit Gmail workflow acceptance. Do not change Gmail data or send mail without a user action.

## Objective

Coordinate the parallel OAuth, Gmail synchronization, Conversations, mailbox-action, settings, and draft workstreams into a runnable local Phase 2 application. Keep Phase 1 demo behavior available. Do not expose `.env` values or start real Google authorization without the user initiating it. Do not add or run tests unless the user asks.

## Status

Phase 2 source integration is substantially implemented. The current backend is running on port 8081; Vite targets it with `EAST_API_TARGET=http://127.0.0.1:8081`. After the latest backend restart, the in-memory app login was restored through Google. Dashboard shows two connected Gmail accounts, both synced. The default combined Inbox/Primary now pages thread metadata directly from Gmail instead of stopping at the 30-message initial sync cache: live UI returned 30 of about 402 estimated conversations and 60 after Load more, with rows from both accounts. Bodies still load only on thread open; no message was opened for this check. The user completed a Star/Unstar round trip and restored the original state. Attachment checks were deferred. Other mailbox mutations, Gmail Drafts save, and send still require user participation. Phase 2 acceptance remains active. East uses an automatically loaded owner-only key file with no routine passphrase prompt or idle lock. Maven compilation passed for 113 sources, the frontend production build passed with 2,078 modules, and Liquibase changesets 001–006 are applied to the configured and clean temporary databases.

Durable recovery for uncertain Gmail draft save/send outcomes is implemented and Liquibase changeset 003 is applied. The PostgreSQL advisory lock enforces one backend process per East database, and the Composer now handles a missing draft after an uncertain send by keeping it locked and telling the user to check Gmail Sent. The Conversations edge cases (nullable expired snippets, the 100-item bulk limit, and mailbox-scoped bulk labels across paginated selections) are fixed. A dedicated servlet server customizer rejects non-loopback address overrides and pins the API to numeric `127.0.0.1`. Gmail sync carries per-thread byte budgets across history pages and checks vault state before local persistence and cursor advancement. Later, the sync-budget placeholder was fixed so opening affected threads performs one on-demand hydration attempt and then respects the cache TTL if the per-thread budget is also exceeded.

## Root-owned integration work

- Wired the vault gate to Google OIDC sign-in, mailbox linking, local settings, the connected Dashboard, and the read-only real mailbox Conversations path; retained fictional demo sign-in.
- Added API contracts and CSRF-aware clients; kept Google client credentials server-side in the protected root `.env`.
- Verified Spring configuration imports `.env` when launched from either repository root or `backend/`; no credential values were read for this check.
- Updated Vite's proxy to target the backend's IPv4 loopback bind, change the backend Host header, and preserve the browser Origin for local request validation.
- Reviewed OAuth state, nonce, OIDC validation, session fixation protection, mailbox ownership, and local disconnect behavior at source level.
- Historical implementation note: the previous design used no-touch vault methods to avoid extending an idle unlock period. The 2026-11-09 local-key decision removed idle locking; those methods now only check that the key is available.
- Hardened AES-GCM helper copies by wiping temporary nonce/ciphertext arrays in encrypt, decrypt, pack, and unpack.
- Started the Compose PostgreSQL service after finding unrelated local containers already using host ports 5432 and 5433. East now uses configurable `EAST_DB_PORT` with loopback port 15432 by default; `pg_isready` confirms the database accepts connections there. Existing containers were left untouched.
- Added `docs/run-local.md` and refreshed README instructions to describe PostgreSQL, Spring Boot, React, and the callback/backend-port match. Google sign-in has now succeeded on the configured local callback; no callback or credential values are copied here.
- Added local PostgreSQL stop, backup, restore, and destructive-reset instructions, with backup dumps excluded from version control.

## Current contracts and constraints

- Root `.env` is ignored and mode `600`; never inspect or print its contents. `.env.example` has placeholders.
- The local OAuth callback and backend port are aligned, and Google sign-in has succeeded. The frontend uses localhost; the backend remains bound to IPv4 loopback. Mailbox actions, attachment fetches, saving drafts, and sending require explicit user actions. No automatic AI work is in Phase 2.
- Never read or print root `.env` values in commands, logs, progress notes, or scratchpads.
- The backend automatically loads its encryption key from the owner-only local key file; there is no routine passphrase prompt or idle lock. Background operations verify key availability and retain existing ownership boundaries.
- The implementation workstreams are integrated; coordinate any future shared-source changes before a build.

## Verification and blockers

- Latest frontend production build passed after the final UI integration (2,078 modules transformed).
- `docker compose config --quiet` passed with the current local configuration.
- `docker compose up -d postgres` initially failed because host ports 5432 and 5433 were already in use by other local containers. After selecting free loopback port 15432, `docker compose up -d postgres`, `docker compose ps`, and `pg_isready` succeeded.
- Maven offline compilation passed for 113 Java sources. Changesets 001–006 have all applied in the local database; a separate empty database also applied all six changesets successfully and was removed.
- The 2026-11-09 attachment privacy/retention follow-up then passed offline Maven compilation for 113 sources and `npm run build` with 2,078 modules transformed. Background sync omits MIME `body.data`; on-demand body and attachment flows discard unselected file-part data before caching. Attachment expiry is checked against its source body. No tests, Gmail field-mask calls, or attachment/browser checks were run.
- At the time of the initial integration audit, no test suites had been added or run. The current backend suite has ten JUnit tests, all passing offline; they cover AES-GCM/AAD, automatic key reload, key-file permissions, and encrypted OAuth/AI-provider/local-draft/staged-attachment data at their persistence boundaries. A send-confirmation test verifies `confirmed: false` stops before repository access. Two tests inspect retention cleanup scheduling and SQL intent, but do not execute against PostgreSQL. End-to-end Gmail workflows remain outside automated coverage. Latest local verification also includes Maven compilation, frontend production builds, Compose config, Liquibase startup on configured and empty databases, source reviews, Google sign-in, two connected mailboxes, read-only sync/search, and scroll pagination across both accounts. The user has completed a Star/Unstar round trip in Conversations; attachment openings/downloads, Gmail Drafts save/read-back/delete, sends, and remaining mailbox mutations are still unverified.

## Next steps

1. With the user present, verify the explicit-click mailbox actions, attachment preview/download, composition and reply flows, save-to-Gmail-Drafts, and user-confirmed send. The attachment check was explicitly deferred; do not fetch real attachments or perform any Gmail mutation/send without the user's explicit action.
2. Continue local database-retention/expiry verification; the current automated suite does not cover purge behavior.
3. Keep Phase 2 active until its acceptance criteria are met; do not begin Phase 3 before then.

# Phase 2 vault and retention audit

Date: 2026-11-09
Status: Source audit and retention hardening complete. Backend compilation passed; Liquibase changeset 007 applied to the configured database; all seven changesets applied to an empty temporary database. No Gmail UI/API access or mailbox actions. The test suite was not run.

## Findings

- **Local key:** `LocalVaultKeyStore` creates/loads a random 32-byte key at `~/.east/vault.key` by default (configurable with `east.vault.key-file`), rejects a symlink at the key file/final parent, sets the `.east` directory to owner `rwx` and key file to owner `rw` on POSIX, writes via an owner-only temporary file and atomic rename, and validates the 32-byte size. This matches the later product decision: automatically load an owner-only local key; no routine passphrase prompt or idle lock.
- **Legacy path:** `VaultService.unlock` and Argon2id derivation remain for a one-time migration from the former passphrase-backed installation. The routine startup uses only the local key file. If a database vault exists but its local key is missing or invalid, the service does not generate a replacement key; reset/recovery is required, avoiding silent data loss.
- **Encryption:** `VaultCryptoService` uses AES/GCM/NoPadding with a fresh 12-byte nonce and 128-bit tag. OAuth refresh tokens, provider API keys, mail-body/snippet payloads, opened attachment bytes, and app-local draft payloads/attachments route through the vault service. Mail data, token, attachment, and draft paths use associated data to bind ciphertext to user/mailbox/message identifiers. Temporary byte arrays are wiped in the inspected paths.
- **Configurable TTL:** database/user default is seven days; settings constrain values to 1–365 days. Body/snippet TTL adjustments update live unexpired rows; expired content is not extended. Cleanup runs every minute without requiring the encryption key and clears expired message ciphertext/snippets while retaining message navigation metadata. Attachment bytes are encrypted and receive the configured TTL after an explicit fetch; reads also check expiry before the periodic purge. An explicit attachment refetch after its source body expires is allowed to establish a fresh attachment TTL.
- **Disconnect:** `MailboxService.disconnect` requires an owner-scoped mailbox delete and invalidates the in-memory access-token cache. FK cascades remove that mailbox's refresh token, thread/message metadata, body/snippet caches, attachment metadata/bytes, labels, local drafts, and staged draft attachments. The saved Gmail Draft itself is not deleted by the local cascade.

## Source-provenance repair

- Liquibase changeset 007 adds `summary_run_source`, tying every future report/source-message mapping to the same app user and mailbox through composite foreign keys. Phase 3 report generation must populate this table for every body used, including narrative-only sources.
- Mailbox disconnect now deletes affected summary runs before the owned mailbox is deleted and source rows cascade. The query includes an ownership guard and also catches older insight-only source links.
- Changing the body-cache lifetime now recalculates narrative expiry using the earliest linked source body expiry, including narrative-only runs. The update is limited to runs with encrypted narrative content so insight-only rows continue to satisfy the existing narrative/ciphertext/expiry constraint.
- Periodic cleanup now expires reports directly from `summary_run_source`, with the existing insight source links retained as a fallback.

## Follow-up TTL edge recheck

- The earlier narrative-only `summary_run` TTL gap is already corrected in the current workspace: `SettingsService.updateBodyCacheDays` derives narrative expiry from the union of direct report sources and insight sources, and `RetentionCleanupRepository.deleteExpiredSummaryRuns` deletes a run when any directly linked source body expires.
- This depends on the future Phase 3 report writer recording **every** source message in `summary_run_source`; a narrative generated without those links would again have no source lifetime to enforce. The composite foreign keys bind each source to the report owner and mailbox.
- This follow-up was source-only: no code or migration change was needed, and no tests or database mutations were run. The migration application recorded below verifies schema application only, not the expiry SQL's behavior.
- Remaining PostgreSQL-backed scenarios for later Phase 3 verification: narrative-only reports with multiple sources; TTL reduction and extension while source bodies are live; an already-expired source that has not yet been purged; and source-message/mailbox deletion cascading to affected reports.

## No runtime claims

`mvn -o -B -f backend/pom.xml -DskipTests compile` passed for all 113 sources. After a local PostgreSQL connection required the approved sandbox escalation, the backend started on port 8081 and Liquibase applied changeset `007-summary-run-source-provenance` successfully (the configured database had changesets 001–006 already applied). A separate empty temporary database applied changesets 001–007 successfully on port 8083 and was then stopped and dropped. No credentials, mailbox identifiers, message content, Gmail requests, or user actions were used. No test suite was run during that source audit.

## PostgreSQL cleanup smoke check — 2026-11-09

- Created a disposable `east_retention_smoke_20261109` database, and started East on port 8084 against it. Startup applied Liquibase changesets 001–008. The configured East database was not used by this instance.
- Seeded synthetic rows with expired body/snippet timestamps, a source-linked summary and insight whose own expiry was still in the future, an attachment fetched before source-body expiry, and an attachment fetched after source-body expiry. The app's scheduled `RetentionCleanupService` ran against those rows.
- Read-only verification showed the expired body nonce/ciphertext/cache timestamps and thread snippet were cleared while sender, subject, and thread/message rows remained. The pre-expiry attachment and source-linked report/insight/source link were removed despite their later own expiry; the post-expiry attachment refetch remained.
- Stopped the isolated Spring Boot process gracefully and dropped the disposable database. No Gmail API request, real account data, user mailbox, or configured database was touched.
- This verifies actual PostgreSQL cleanup for body/snippet expiry, metadata preservation, attachment source-expiry behavior, and derived-source expiry. The initial range is 1–365 days, with a seven-day default.

## Configurable TTL PostgreSQL acceptance — 2026-11-09

- Added opt-in `RetentionSettingsPostgresAcceptanceTest`. It requires `EAST_RUN_POSTGRES_ACCEPTANCE=true` and rejects database names outside the `east_retention_test_*` prefix. The Spring context sets the Gmail sync initial delay to ten minutes so the synthetic account cannot trigger a sync during the test.
- The test uses `SettingsService` against an isolated PostgreSQL database. It verifies the default is seven days, changes the setting to 30 days, then reduces it to two days, and checks expiry intervals for cached message bodies, thread snippets, fetched attachments, and source-linked summary/insight records after each update.
- `env EAST_RUN_POSTGRES_ACCEPTANCE=true EAST_RETENTION_TEST_DB=east_retention_test_20261109 EAST_DB_PORT=15432 mvn -o -B -Dtest=RetentionSettingsPostgresAcceptanceTest test` passed: 1 test, 0 failures/errors/skips. The disposable database applied migrations 001–008 and was dropped after the run.
- `env -u EAST_RUN_POSTGRES_ACCEPTANCE -u EAST_RETENTION_TEST_DB mvn -o -B test` passed the ordinary suite: 27 passed, the opt-in PostgreSQL test skipped, 0 failures/errors. The opt-in test therefore does not touch a database during routine Maven runs.

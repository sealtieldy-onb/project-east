# Phase 2 vault and retention source review

Date: 2026-11-09
Scope: source-only review of vault, cache retention, and local security against current product decisions. No secrets, browser, Gmail, database, tests, builds, or application files were accessed or changed.

## Current decisions

- East is localhost-only and supports one app user per installation with multiple Gmail mailboxes.
- No routine passphrase prompt or idle lock. Generate/load a random local encryption key automatically from an owner-only file. Keep the passphrase endpoint only for one-time migration of older installations.
- Encrypt Gmail refresh tokens, the saved AI-provider key, cached message bodies/snippets, fetched attachment bytes, and unsaved local draft content/files at rest. The default cache lifetime is seven days and is configurable. Expired bodies, attachments, and source-derived summaries/insights expire; minimal inbox metadata remains. Local drafts and their staged files remain until saved/deleted as separately decided.

## Source evidence

- **Local key and encryption:** `LocalVaultKeyStore.java:21-41,44-69,71-101` generates/loads a 32-byte-key file under `~/.east` by default and sets owner-only POSIX permissions on the file and parent directory. `VaultService.java:46-87,162-190` auto-loads the key on startup and retains a one-time legacy passphrase migration path. `VaultCryptoService.java:16-58` uses AES-GCM with authenticated associated data. `VaultSecurityTest.java:73-196` contains automatic-load, permission repair, and symlink-parent test cases (not run in this review).
- **Mailbox and provider credentials:** `MailboxService.java:59-95` encrypts refresh tokens before persistence; `SettingsService.java:54-115` encrypts the AI key and returns only configured/not-configured status. `AIProviderUpdateRequest.java:6-16` redacts the key from `toString`; `GoogleOAuthProperties.java:17-23` similarly avoids logging OAuth client values. OAuth client credentials are configured through the ignored local `.env`; `docs/google-oauth-setup.md:35-43` documents `chmod 600` and the local key-file caveat.
- **Cached content and ownership binding:** `GmailCacheAad.java:11-22` binds body/snippet ciphertext to user, mailbox, and Gmail identifiers. `GmailSyncService.java:655-688` serializes selected message bodies/headers before encrypting and wipes the plaintext byte array. `AttachmentContentService.java:51-137,171-199` checks ownership, uses attachment-specific AAD, caches fetched bytes encrypted with the configured TTL, and wipes transient byte arrays. `DraftAad.java:11-23` and `DraftService.java:118-149,203-225,1438-1470` bind and encrypt local draft/file payloads.
- **Retention:** `001-initial-local-schema.sql:11-19` sets `body_cache_ttl_days` default to 7 and limits it to 1–365. `SettingsService.java:126-214` updates body, snippet, attachment, summary, and insight expiry. `RetentionCleanupService.java:24-33` schedules a one-minute purge; `RetentionCleanupRepository.java:18-95` deletes expired source-derived records and attachments, clears cached bodies/snippets, and leaves basic message/thread metadata. `001-initial-local-schema.sql:46-103,109-135,210-253` defines those metadata/content and expiry fields.
- **Local boundary and reset:** `LoopbackServerConfiguration.java:14-29` forces the backend onto IPv4 loopback. `LocalSecurityConfiguration.java:60-100` requires authentication for app APIs and configures CSRF/session protections; `LocalRequestBoundaryFilter.java:22-66,69-109` restricts host/origin and sets response security headers. `LocalDataResetService.java:10-40` truncates East-owned application data while preserving Liquibase history; `VaultService.java:152-160` requires an exact reset confirmation and rotates the key.

## Findings and coverage gaps

- No concrete blocker was identified in the source reviewed.
- This review did not run tests or runtime checks. Relevant coverage exists in `VaultSecurityTest.java`, `SettingsEncryptionTest.java`, `MailboxTokenEncryptionTest.java`, `DraftPersistenceEncryptionTest.java`, `RetentionCleanupTest.java`, and the opt-in PostgreSQL `RetentionSettingsPostgresAcceptanceTest.java`; this review does not claim those tests passed.
- On non-POSIX filesystems, `LocalVaultKeyStore.java:115-123,125-142` falls back to inherited OS ACLs rather than verifying owner-only access. The app documents the POSIX limitation; confirm ACL behavior on any non-POSIX host before claiming the same key-file guarantee there.
- **Memory-residue hardening:** `GoogleAccessTokenService.java:70-108` wipes decrypted refresh-token bytes, but converts them to an immutable Java `String` at line 78 for the token request. That string cannot be explicitly cleared. This does not change the at-rest protection and is a hardening note, not a blocker.

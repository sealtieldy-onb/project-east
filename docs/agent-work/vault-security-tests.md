# Vault security tests

## Objective

Add focused automated coverage for the Phase 2 local encryption boundary and owner-only local key file.

## Coverage added

- `VaultCryptoService` AES-GCM encryption and decryption round-trip.
- Ciphertext does not contain the test plaintext, and the authentication tag rejects both a changed AAD value and tampered ciphertext.
- A newly initialized vault creates a random local key; a new `VaultService` instance reloads it and decrypts previously encrypted local data without a startup passphrase.
- `LocalVaultKeyStore` reads the generated 32-byte key and applies owner-only POSIX file/directory permissions where the filesystem supports them.
- `SettingsService.putAIProvider` sends only nonce/ciphertext bytes to its JDBC boundary; the captured ciphertext decrypts through the vault only with the AI-key AAD.
- `MailboxService.connect` sends only nonce/ciphertext bytes to its mailbox-repository boundary; the captured refresh-token ciphertext decrypts only with the app-user/mailbox/Google-subject AAD.
- `DraftService.create` persists only encrypted draft-payload bytes; the test decrypts the synthetic record with the app-user/draft/mailbox AAD and confirms the payload rejects a different user identity.
- `DraftService.addAttachment` persists only encrypted staged-file bytes; the test decrypts the synthetic bytes with the app-user/draft/attachment AAD and confirms they reject a different attachment ID.
- `DraftService.send` rejects a draft without `confirmed: true` before any repository access.
- Retention cleanup tests verify the scheduled service's clock cutoff and that repository SQL clears expired ciphertext while retaining message/thread metadata rows.

## Verification

- `mvn -o -B -f backend/pom.xml test` passed: 10 tests, 0 failures, 0 errors, 0 skipped.
- The tests use temporary filesystem paths, in-memory repositories, and capturing JDBC boundaries; they do not read or mutate the configured East database or contact Google, Gmail, or an AI provider. OAuth identity/token values, draft payloads, and attachment bytes are synthetic test data.

## Limits

- These tests cover the core encryption/key mechanism and the OAuth, AI-key, local-draft, and staged-attachment persistence boundaries. Retention assertions inspect SQL intent and scheduling but do not execute against PostgreSQL.
- PostgreSQL-backed expiry/purge behavior, Liquibase reproducibility, Gmail mutations, Gmail Drafts save/readback, and send/reconciliation remain separate acceptance checks.

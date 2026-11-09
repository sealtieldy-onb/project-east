# Local key migration workstream

## Objective

Remove routine startup passphrase entry and idle locking while preserving encrypted local data and keeping East localhost-only.

## Decision

New installations generate a random 256-bit key and save it outside PostgreSQL in `~/.east/vault.key` (override with `EAST_VAULT_KEY_FILE`). POSIX permissions are restricted to the current OS account. East loads the key at startup and holds it in memory while running. Same-account processes can access this key; localhost is not a security boundary against them.

Existing databases from the passphrase-based version can migrate without data loss: the user enters the existing passphrase directly in East once, East validates it against the encrypted verification marker, and writes the derived key to the owner-only key file. East never stores the passphrase. If migration is not possible, destructive local reset remains available.

## Implementation notes

- Added `LocalVaultKeyStore` for safe local key read/write/delete and optional path configuration.
- `VaultService` auto-initializes fresh installs and loads valid existing local keys at startup; it only leaves a legacy database gated until migration.
- Removed idle timer, activity endpoint, setup endpoint, and lock endpoint usage. The migration and destructive reset UI remain only for existing legacy data or key loss.
- Updated local security filter and frontend vault state; Google/session data is cleared if migration is required.
- Updated AGENTS, product/technical plans, roadmap, decisions, setup guides, README, and progress notes.

## Verification

- `mvn -o -B -f backend/pom.xml -Dmaven.repo.local=/private/tmp/east-m2 compile` — passed, 113 Java sources.
- `npm run build` — passed, 2,078 modules transformed.
- No tests were run. The backend still needs restart and the current legacy vault needs one user-entered migration before the read-only Gmail sync retry.

## Next step

The user chose to erase the legacy local data rather than preserve it through passphrase migration. The destructive reset completed through East's exact-phrase Settings flow. East generated a fresh owner-only 32-byte key file; the previous mailbox link and local records are gone, while Gmail itself was unchanged. The user continued Google sign-in after the warning and connected one mailbox. Its read-only sync is current; no routine passphrase prompt or idle lock is used. Phase 2 acceptance still needs a second mailbox and user participation in the explicit-click mailbox, attachment, draft-save, and send workflows.

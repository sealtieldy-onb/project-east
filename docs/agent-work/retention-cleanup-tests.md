# Cache retention cleanup tests

## Objective

Cover the scheduled expiry-cleanup orchestration and SQL intent without touching East's configured PostgreSQL database or connected mailboxes.

## Implementation

- `RetentionCleanupTest` verifies that the service passes one clock-derived cutoff through all five cleanup steps: summary runs, insights, attachments, message bodies, and thread snippets.
- A capturing `JdbcTemplate` verifies the cleanup SQL targets expired records, purges insights/source-derived records with expired source bodies, deletes stale attachment rows/bytes, and clears body/snippet ciphertext and expiry fields while retaining thread/message metadata rows.
- These are unit tests over SQL text and bind arguments. They do not execute the SQL against PostgreSQL.

## Verification

- `mvn -o -B -f backend/pom.xml test` passed: 10 tests, 0 failures, 0 errors, 0 skipped.
- An attempt to inspect the Compose PostgreSQL service found that the Docker API socket is unavailable to this process; `127.0.0.1:15432` did not respond. No database credentials were read, and no database was modified.

## Limits / next acceptance

- Actual cleanup SQL has been exercised by the PostgreSQL smoke scenario in `phase2-retention-acceptance.md`: body/snippet clearing with metadata retained, attachment deletion based on source-body expiry, retention of an explicitly refetched attachment, and source-derived report/insight deletion all passed in an isolated database. The opt-in `RetentionSettingsPostgresAcceptanceTest` also passed against a disposable database, checking the seven-day default plus 30-day extension and two-day reduction across body, snippet, attachment, and dependent report/insight expiries. Encrypted payload decryption after changed TTLs remains unverified.
- Attachment browser/runtime checks remain explicitly deferred by the user. Do not use live attachments or perform Gmail mutations/sends without the user's action.

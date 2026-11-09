# Persistent sessions and cache-first Conversations

Date: 2026-11-10
Branch: `feature/persistent-mail-cache`

## Scope

- Keep the local authenticated Spring Security context and session-bound OAuth state in PostgreSQL through Spring Session JDBC, with a 60-day inactive timeout and 60-day session cookie lifetime. Preserve the existing CSRF cookie/header checks, HttpOnly/SameSite session cookie, `ChangeSessionIdAuthenticationStrategy`, and explicit logout invalidation.
- Encrypt serialized Spring Session attribute bytes with the existing installation key through the named Spring Session conversion service. Use a versioned authenticated AES-GCM envelope/AAD, wipe serialized and crypto temporary byte arrays, and treat invalid/unavailable-key attributes as absent so vault recovery stays reachable. The raw random `SESSION_ID` column remains an opaque bearer lookup identifier; only session attributes are encrypted.
- Add Liquibase changeset 010, `persistent-session-cache-policy`, for the Spring Session tables and the 60-day email-cache default. Promote still-valid rows created under the former seven-day default, preserve other configured values, and do not extend expired content.
- Render ownership-scoped cached conversation pages and still-valid encrypted bodies before the Gmail refresh. Fetch full message bodies only for absent or expired body caches. Keep attachment bytes and email content encrypted at rest.
- Search local cached metadata first for supported plain-text queries, mark those rows as a partial saved subset, then replace them with Gmail's full-mailbox results and page token. Gmail operators and quoted phrases remain Gmail-only.

## Recovery notes

- Work is isolated to the local `feature/persistent-mail-cache` branch in the managed `persistent-mail-cache` worktree. No remote, push, or merge is configured or performed. The branch will be rebased on current local `main` before independent review.
- Changeset 010 is owned by this workstream. It creates `SPRING_SESSION` and `SPRING_SESSION_ATTRIBUTES`; `spring.session.jdbc.initialize-schema=never` keeps Liquibase as the only schema initializer. Boot 4's JDBC-session auto-configuration is supplied by `spring-boot-starter-session-jdbc`; the direct `spring-session-jdbc` module alone does not register the repository, and the legacy `spring.session.store-type` property is not used.
- Spring Session attributes (`SPRING_SESSION_ATTRIBUTES.ATTRIBUTE_BYTES`) are encrypted with version 1 AES-GCM and associated data binding the envelope header to the session-attribute domain. `PRINCIPAL_NAME` contains only the internal user UUID. `SESSION_ID` remains the random bearer lookup value required by Spring Session; with loopback binding, owner-only local files, and the same-OS-account threat model, do not describe the whole session row as encrypted.
- Destructive vault reset truncates both `spring_session_attributes` and `spring_session`; stale-key ciphertext and all other browser sessions are removed and users must sign in again. Explicit logout continues to invalidate only the current session.
- No live app or Gmail connection was used. No account identity, credentials, tokens, or real email content are included here.

## Assumptions and limits

- The old schema has no flag distinguishing a user-selected seven-day setting from the former seven-day default. Changeset 010 treats `body_cache_ttl_days = 7` as the legacy default and moves it to 60; non-seven-day settings remain unchanged. This is the only available source-of-truth distinction in the current schema.
- Local search accepts only plain text with letters/numbers and a small safe set of punctuation. `_`, `%`, backslash, Gmail operators, and quoted phrases do not use the local `ILIKE` path. The local subset checks cached thread subject and cached sender fields, not encrypted snippets or bodies, and is never claimed complete. Gmail search remains authoritative.
- Conversations displays saved local pages immediately and uses the cache-only cursor for Load more/scroll pagination while those results remain active. If Gmail refresh succeeds, the UI aborts an in-flight local page and replaces the list/cursor with Gmail's authoritative page token; if refresh fails, the local cursor remains available for stale/offline browsing.
- Cache-only thread detail is returned only when at least one encrypted message body is still valid. Expired or absent bodies proceed to the Gmail path; the read service first requests metadata, then requests `format=full` only for body IDs that are absent or expired. A fully cached thread makes no full-message body request.
- A live detail open now performs one Gmail metadata refresh and persists thread/message headers, timestamps, snippets, and labels even when body caches are valid. Cached body bytes remain untouched and attachment metadata writes are disabled for the cached-ID refresh path. Synthetic gateway regression covers updated subject/sender/date/label state, retained-body sentinel, no attachment replacement, and zero full-body requests. A recording-JDBC repository regression confirms metadata/label upserts execute with the body-preservation guards intact and that no attachment SQL runs for incomplete attachment metadata.
- Session conversion regression uses a synthetic SecurityContext and OAuth nonce to verify encrypted stored bytes, round-trip restore, and absent-attribute behavior for unavailable key, tampering, and legacy plaintext. The opt-in PostgreSQL restart acceptance also checks persisted attributes contain no synthetic identity/nonce plaintext and can restore the OAuth nonce after creating a fresh application context.
- Local reset unit regression asserts the reset SQL includes both Spring Session tables. The reset SQL is executed only by tests with a recording JdbcTemplate in this environment; actual Liquibase/PostgreSQL reset execution remains opt-in.
- The PostgreSQL restart acceptance uses a disposable `east_session_test_*` database and only synthetic rows. It is opt-in to avoid accidentally using the configured local East database.

## Checks and results

- `backend: mvn -q -Dtest=SessionAttributeEncryptionTest,LocalDataResetServiceTest,GmailSyncStateTest,GmailSyncRepositoryOnDemandTest test` — passed; 10 tests run, 0 failures, 0 errors. This verifies encrypted session-attribute round-trip/no-plaintext behavior, unavailable-key and tamper fallback, session-table reset inclusion, live detail metadata refresh with zero full-body requests, and the repository upsert path preserving valid bodies and skipping attachment metadata replacement.
- Final `backend: mvn -q test` with both opt-in PostgreSQL acceptance flags set for the project-provided disposable databases — passed; 78 tests run, 0 failures, 0 errors, 0 skipped.
- `frontend: npm ci --offline --ignore-scripts` — passed from the local npm cache; 42 packages added, 0 reported vulnerabilities. No network access was used.
- `frontend: npm run build` — passed; TypeScript build and Vite production build completed against the rebased branch, transforming 2,081 modules.
- Final `git diff --check` — passed after the starter and acceptance-fixture corrections.
- `PersistentSessionPostgresAcceptanceTest` passed against the project's disposable loopback PostgreSQL fixture (not the configured East database). It verifies a Boot-created 60-day JDBC session, UUID-only principal index, encrypted envelope bytes with no synthetic identity/nonce plaintext, authenticated `/api/v1/me` and OAuth nonce restoration across fresh app contexts using the serialized `EASTSESSION` cookie value, and CSRF-protected logout invalidation/cookie expiry. The fixture uses a reserved loopback port rather than `server.port=0`, because the existing Host boundary validates the configured server port; logout also sends the required local Origin header.
- `RetentionSettingsPostgresAcceptanceTest` passed against its separate disposable PostgreSQL database. Both acceptance contexts reported Liquibase up to date with all 10 current changesets; the session acceptance cleaned up only its synthetic user, session, and temporary vault marker.
- No configured East database or live Gmail account was used.

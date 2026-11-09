# Persistent sessions and cache-first Conversations

Date: 2026-11-10
Branch: `feature/persistent-mail-cache`

## Scope

- Keep the local authenticated Spring Security context and session-bound OAuth state in PostgreSQL through Spring Session JDBC, with a 60-day inactive timeout and 60-day session cookie lifetime. Preserve the existing CSRF cookie/header checks, HttpOnly/SameSite session cookie, `ChangeSessionIdAuthenticationStrategy`, and explicit logout invalidation.
- Add Liquibase changeset 010, `persistent-session-cache-policy`, for the Spring Session tables and the 60-day email-cache default. Promote still-valid rows created under the former seven-day default, preserve other configured values, and do not extend expired content.
- Render ownership-scoped cached conversation pages and still-valid encrypted bodies before the Gmail refresh. Fetch full message bodies only for absent or expired body caches. Keep attachment bytes and email content encrypted at rest.
- Search local cached metadata first for supported plain-text queries, mark those rows as a partial saved subset, then replace them with Gmail's full-mailbox results and page token. Gmail operators and quoted phrases remain Gmail-only.

## Recovery notes

- Work is isolated to the local `feature/persistent-mail-cache` branch in the managed `persistent-mail-cache` worktree. No remote, push, or merge is configured or performed. The branch will be rebased on current local `main` before independent review.
- Changeset 010 is owned by this workstream. It creates `SPRING_SESSION` and `SPRING_SESSION_ATTRIBUTES`; `spring.session.jdbc.initialize-schema=never` keeps Liquibase as the only schema initializer. The unsupported `spring.session.store-type` property was removed; Spring Boot auto-configuration is expected to select JDBC from the single `spring-session-jdbc` module.
- No live app or Gmail connection was used. No account identity, credentials, tokens, or real email content are included here.

## Assumptions and limits

- The old schema has no flag distinguishing a user-selected seven-day setting from the former seven-day default. Changeset 010 treats `body_cache_ttl_days = 7` as the legacy default and moves it to 60; non-seven-day settings remain unchanged. This is the only available source-of-truth distinction in the current schema.
- Local search accepts only plain text with letters/numbers and a small safe set of punctuation. `_`, `%`, backslash, Gmail operators, and quoted phrases do not use the local `ILIKE` path. The local subset checks cached thread subject and cached sender fields, not encrypted snippets or bodies, and is never claimed complete. Gmail search remains authoritative.
- Conversations displays saved local pages immediately and uses the cache-only cursor for Load more/scroll pagination while those results remain active. If Gmail refresh succeeds, the UI aborts an in-flight local page and replaces the list/cursor with Gmail's authoritative page token; if refresh fails, the local cursor remains available for stale/offline browsing.
- Cache-only thread detail is returned only when at least one encrypted message body is still valid. Expired or absent bodies proceed to the Gmail path; the read service first requests metadata, then requests `format=full` only for body IDs that are absent or expired. A fully cached thread makes no full-message body request.
- The PostgreSQL restart acceptance uses a disposable `east_session_test_*` database and only synthetic rows. It is opt-in to avoid accidentally using the configured local East database.

## Checks and results

- `backend: mvn -q test` — passed; 74 tests run, 0 failures, 0 errors, 2 opt-in PostgreSQL acceptance tests skipped. This compiles the new session-restart/search acceptance and runs the synthetic Gmail gateway regression asserting metadata-only thread reads make zero full-message body requests when the body cache is valid.
- `frontend: npm ci --offline --ignore-scripts` — passed from the local npm cache; 42 packages added, 0 reported vulnerabilities. No network access was used.
- `frontend: npm run build` — passed; TypeScript build and Vite production build completed against the rebased branch, transforming 2,081 modules.
- `git diff --check` — passed.
- `PersistentSessionPostgresAcceptanceTest` is present and compiled. It checks that Boot creates a 60-day JDBC session by default, the JDBC repository indexes the authenticated principal by its internal user UUID, a fresh application context restores `/api/v1/me` using the same session cookie, and CSRF-protected logout expires the HttpOnly/SameSite session cookie and invalidates that session. It was not executed because `EAST_RUN_SESSION_POSTGRES_ACCEPTANCE=true` and a valid `EAST_SESSION_TEST_DB=east_session_test_*` are not configured in this environment. Thus live database restart/filter behavior and Liquibase execution remain unverified here.
- `RetentionSettingsPostgresAcceptanceTest` is also opt-in and was skipped; the configured database was not used.

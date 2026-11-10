# Session cookie recovery

Date: 2026-11-10

## Change

- Added a request filter at `SessionRepositoryFilter.DEFAULT_ORDER - 1`, so East session cookies are checked before Spring Session can resolve an ID from PostgreSQL.
- The filter accepts only the canonical Base64 representation of a lowercase UUID v4 session ID, matching Spring Session's `UuidSessionIdGenerator`. It removes malformed, duplicate, and legacy cookie values from the request and expires the configured cookie through the same `DefaultCookieSerializer` used by Spring Session.
- The serializer's Base64 mode is set explicitly. The filter decodes and checks bytes itself, then wipes the temporary byte array; this avoids passing malformed values to the default decoder, which logs malformed raw values at debug level.
- Valid 60-day sessions remain eligible for restoration, and the existing explicit logout path still invalidates server-side state.
- The opt-in PostgreSQL acceptance test now obtains its valid cookie from the app's OAuth-start response using synthetic OAuth configuration (no Google request), reuses it across restart, checks an embedded-NUL session ID receives 401 plus cookie expiry, and retains the valid restore/cache/logout assertions.

## Verification

- `mvn -o -B -f backend/pom.xml -Dtest=EastSessionCookieRecoveryFilterTest,PersistentSessionPostgresAcceptanceTest test`: passed; 3 focused synthetic tests passed, 0 failures/errors, and the PostgreSQL acceptance test was skipped because its opt-in environment variable was not set. The acceptance source compiled, including the latest redirect URI fix.
- `mvn -o -B -f backend/pom.xml -DskipTests compile`: passed; the prior focused-test run compiled 144 main Java sources and 37 test sources, and this explicit compile completed with sources up to date.
- `git diff --check`: passed after the source and documentation edits. The new untracked source and scratchpad files were also checked for trailing whitespace.
- The earlier fixture-only OAuth redirect returned `503` because its callback URI did not match the test server's random port; the developer fixed that URI. QA reran the opt-in acceptance against the isolated `east_session_test_cookie_recovery` database on port `25432`, passing only the repository `.env` database variables to the test child process. Maven output was captured, with no credential or cookie values emitted.
- The first QA execution was blocked when the sandbox denied the app's loopback bind (`Operation not permitted`). The authorized retry with loopback access passed: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`. This verifies the actual app-issued cookie across two Spring contexts, PostgreSQL session restoration, malformed-cookie rejection, synthetic cache-only search, and logout. No configured East database, Gmail, or personal mailbox was used.
- The isolated `east-session-cookie-qa` Compose project and its dedicated PostgreSQL volume were removed after the passing rerun; the configured East database was left running and untouched.

## Changed files

- `backend/src/main/java/com/east/app/config/EastSessionCookieRecoveryFilter.java`
- `backend/src/main/java/com/east/app/config/SessionCookieRecoveryConfiguration.java`
- `backend/src/test/java/com/east/app/config/EastSessionCookieRecoveryFilterTest.java`
- `backend/src/test/java/com/east/app/auth/PersistentSessionPostgresAcceptanceTest.java`
- `docs/product-plan.md`
- `docs/technical-plan.md`
- `docs/implementation-roadmap.md`
- `docs/decisions-and-open-questions.md`
- `docs/project-progress.md`
- `docs/agent-work/session-cookie-recovery-qa.md`

No schema change, migration, commit, or branch switch was made. The branch is `fix/session-cookie-recovery`.

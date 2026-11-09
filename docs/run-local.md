# Run East locally

East is a localhost-only application. Keep the browser host, OAuth redirect URI, and Google Cloud Console authorized redirect URI aligned. Never share the root `.env`; it contains local Google OAuth credentials and database configuration.

## Prerequisites

- Java 21
- Maven 3.9 or newer
- Docker Compose
- Node.js 22.12 or newer and npm

## Start PostgreSQL

From the project root:

```sh
docker compose up -d postgres
```

The database binds to `127.0.0.1:15432` by default. `EAST_DB_PORT` can change that port; Compose and Spring Boot read the same `.env` setting.

### Stop, back up, restore, or reset PostgreSQL

- Stop PostgreSQL and preserve its data volume: `docker compose stop postgres`. Start it again with `docker compose up -d postgres`.
- Create a custom-format backup from the running container: `umask 077 && docker compose exec -T postgres sh -c 'pg_dump -Fc -U "$POSTGRES_USER" -d "$POSTGRES_DB"' > east-backup.dump`. Keep this file private; it contains local account metadata and encrypted app data. The root `.gitignore` excludes `*.dump`.
- Restore that backup only after stopping the East backend, with `docker compose exec -T postgres sh -c 'pg_restore --clean --if-exists --no-owner --exit-on-error -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < east-backup.dump`. This replaces objects/data in the configured East database.
- Destructive database reset: `docker compose down -v` removes the PostgreSQL data volume and all East local database contents. Then run `docker compose up -d postgres`; the backend reapplies Liquibase migrations on next start. This cannot be undone without a backup.

The in-app local-data reset removes East records and its local encryption key while leaving PostgreSQL and its volume in place; Gmail data is not changed. East stores its random local encryption key in `~/.east/vault.key` with owner-only permissions on POSIX systems. Set `EAST_VAULT_KEY_FILE` only if you need a different location, and protect that file and its parent directory. Losing the key requires a destructive local-data reset and reconnecting accounts.

## Start the backend

In a terminal from the project root:

```sh
cd backend
EAST_SERVER_PORT=8081 EAST_GOOGLE_REDIRECT_URI=http://localhost:8081/login/oauth2/code/google mvn spring-boot:run
```

Spring Boot imports the root `.env`, which holds the Google client credentials and database password. The command-line settings above override only the API port and callback for this process; Liquibase applies pending migrations during startup. Persist those two settings in `.env` if you prefer starting the backend with plain `mvn spring-boot:run`.

If port 8080 is occupied, choose an unused local port with `EAST_SERVER_PORT` in `.env`. Update `EAST_GOOGLE_REDIRECT_URI` to `http://localhost:<port>/login/oauth2/code/google`, register the same URI in Google Cloud Console, and point the Vite API proxy at the backend port as described below. East keeps Google sign-in disabled whenever the configured redirect port does not match the actual backend port.

## Start the frontend

In a second terminal from the project root:

```sh
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173` and use that hostname for the whole session. Vite binds to IPv4 loopback; the local request boundary and OAuth callback use `localhost` as the trusted browser host. The API proxy defaults to `http://127.0.0.1:8080`; if you chose another backend port, set `EAST_API_TARGET` for the dev command. For example, with the backend on port 8081:

```sh
EAST_API_TARGET=http://127.0.0.1:8081 npm run dev
```

Then open the app. New installations generate and load the local encryption key automatically. No passphrase or idle lock is used. The key file is readable by the same operating-system account that runs East, so keep access to that account and its files private. An existing database from the previous passphrase-based version asks for the old passphrase once to migrate its key; East does not store the passphrase. Use the fictional demo entry when Gmail is not configured or connected.

## Google OAuth setup

Follow [Google OAuth setup](google-oauth-setup.md). Add the redirect URI for the backend port to the Web OAuth client in Google Cloud Console and set the matching URI in the root `.env`. The browser should use `localhost`, not `127.0.0.1`, for its host to match the callback and session cookies. Do not click Google sign-in until the callback and backend port agree.

## Current verification state

The 2026-11-09 Phase 2 baseline run of `mvn -o -B -f backend/pom.xml test` passed 54 of 55 tests; the opt-in PostgreSQL acceptance test was skipped. It includes synthetic two-mailbox outbound routing, single-thread and bulk action matrices, Reply/Reply all/Forward source behavior including forwarded attachment staging/removal, and local/Gmail-backed draft reconciliation coverage including confirmed-completed saved-send recovery. That acceptance test passed separately: it verified seven-day default, extension to 30 days, and reduction to two days for message bodies, snippets, attachments, and linked report/insight expiries. A disposable PostgreSQL smoke check also exercised actual scheduled body/snippet cleanup with metadata retained, attachment source-expiry handling, and derived-record deletion. The frontend production build passed after the sync-permission reconnect UX change. The current changeset and database verification is recorded below. East runs locally with the backend on port 8081 and frontend on port 5173; Vite uses `EAST_API_TARGET=http://127.0.0.1:8081` because port 8080 belongs to another local service. Conversations pages through Gmail folder/category metadata on demand instead of limiting those views to the 30-message initial sync cache. The user confirms all emails load; assistant read-only paging reached 180 after the latest rebuild and 1,590 in an earlier session. Full message bodies remain on demand when a user opens a thread. A prior backend restart followed by Google sign-in showed both accounts as Synced. The user confirmed Star/Unstar, Read/Unread, label lifecycle, Spam/Not spam, and draft creation/save/readback/send/trash. The user defers all remaining checks requiring their input; do not prompt for attachments, Archive/Trash restoration, Reply, mixed-account bulk labels, or Forward unless reopened. Focused tests cover mailbox action ownership and routing, draft send preconditions and local/Gmail reconciliation, Reply/Reply all/Forward source handling, and attachment cache/source expiry. East uses an automatically loaded owner-only local key file and has no routine passphrase prompt or idle lock.

**Current local check (2026-11-09):** A backend restart to load token-aware Gmail authorization handling connected to the configured database and confirmed Liquibase 001–008 are current. The restart cleared the in-memory app session; the user subsequently signed in again. The latest read-only Dashboard check shows both accounts Synced. Repeated **Load more** actions in an earlier run advanced combined Inbox/Primary from 30 to 1,590 rows without a request error or reset at 660. Gmail's approximate total stayed around 402. The frontend displays loaded count and page availability without the estimate. After a production build reset the view to 30, a new read-only check advanced combined Inbox · Primary through 60, 90, 120, 150, and 180 rows. The user confirms that all emails load; we stopped further fetching at their request, without independently checking the terminal cursor. A disposable empty database also applied 001–008 and was removed afterward. No thread was opened and no Gmail mutation occurred during the latest UI check. The user asked to be notified whenever Google sign-in is required; both accounts remained signed in during this check.

**Local platform checks (2026-11-09):** The documented PostgreSQL backup/restore commands were exercised using two disposable databases containing only synthetic probe rows. A custom-format dump restored successfully with `pg_restore --clean --if-exists --no-owner --exit-on-error`, the restored values matched, and both databases and the temporary dump were removed; the configured East database was not touched. The no-OAuth demo was also started with Google credentials unset and an isolated disposable database/key. The auth configuration endpoint reported Google sign-in unavailable, and the browser successfully opened the fictional Dashboard and Conversations with three sample accounts. The temporary browser tab, app processes, database, and key were cleaned up. No real Gmail data was accessed in these checks.

**Phase 3 database update (2026-11-10):** After user authorization, Liquibase changeset 009 was applied to the configured East database; changesets 001–009 are now recorded as executed. A disposable PostgreSQL database applied all nine changesets and passed the opt-in synthetic acceptance for cache TTL updates, expired report/insight cleanup, and mailbox-disconnect cleanup, including combined and empty-mailbox report scopes. The disposable databases were removed. The final backend suite passed 71 tests with one opt-in PostgreSQL test skipped; the PostgreSQL acceptance test passed separately. The configured database has no report/source rows. A second app instance was not started because the existing East process held the single-instance guard; it was left running. No live Gmail or provider request was made.

### Optional PostgreSQL retention acceptance

The default Maven suite skips this integration test. To exercise cache-TTL updates against PostgreSQL, first ensure Compose PostgreSQL is running, then create a disposable database whose name starts with `east_retention_test_`:

```sh
docker compose exec -T postgres psql -U east -d postgres -c "CREATE DATABASE east_retention_test_local OWNER east;"
cd backend
env EAST_RUN_POSTGRES_ACCEPTANCE=true EAST_RETENTION_TEST_DB=east_retention_test_local EAST_DB_PORT=15432 mvn -o -B -Dtest=RetentionSettingsPostgresAcceptanceTest test
cd ..
docker compose exec -T postgres psql -U east -d postgres -c "DROP DATABASE east_retention_test_local;"
```

Use the same database port and role configured for your local Compose service. The test verifies the seven-day default plus TTL extension and reduction for synthetic rows; it delays Gmail sync and does not access a real mailbox. Drop the disposable database after the run, including after a failure.

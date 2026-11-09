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

Maven offline compilation and `mvn -o -B -f backend/pom.xml test` passed previously; the current suite has 12 tests covering AES-GCM/AAD, local key reload/permissions, encrypted OAuth/AI credentials, encrypted local drafts/staged attachments, explicit-send confirmation, saved-draft send request binding, inline attachment byte retention/wiping, and retention-cleanup scheduling/SQL intent. The retention tests do not execute SQL against PostgreSQL. The frontend production build passed. Liquibase changesets 001–007 are applied to the configured local PostgreSQL database; an empty temporary database also applied all seven changesets successfully before it was stopped and dropped. East runs locally with the backend on port 8081 and frontend on port 5173; Vite uses `EAST_API_TARGET=http://127.0.0.1:8081` because port 8080 belongs to another local service. Conversations pages through Gmail folder/category metadata on demand instead of limiting those views to the 30-message initial sync cache. Live read-only verification loaded 30, 60, and 90 of about 402 combined Inbox/Primary conversations by scrolling; the current All categories Inbox loaded 60 of about 402 across both accounts. The list fetches another 30 metadata rows as the user scrolls or clicks Load more and can recover when its in-memory page cursor expires. Full message bodies remain on demand when a user opens a thread. Dashboard reports two synced accounts. The user completed a Star/Unstar round trip and restored the original state. Attachment checks were deferred; Gmail Drafts saves, sends, and remaining mailbox actions have not been exercised. Saved Gmail Drafts are sent with the exact fingerprinted snapshot in a replacement `drafts.send` request. PostgreSQL-backed expiry behavior and live Gmail workflows remain unverified. East uses an automatically loaded owner-only local key file and has no routine passphrase prompt or idle lock.

**Current local check (2026-11-09):** `npm run build` passed; `mvn -o -B -f backend/pom.xml test` passed all 12 tests; and `docker compose config --quiet` passed. PostgreSQL was previously confirmed healthy, and the configured database reports Liquibase changesets 001–007. Earlier read-only aggregates confirm one app user, two synced mailbox rows with distinct color indexes, and 413 cached thread metadata rows; the owner-only key file has mode `0600`. The latest signed-in read-only Conversations check showed 30 of about 201 Inbox · Primary conversations in each account scope; the combined view advanced from 30 to 90 of about 402. All mail · All categories separately showed 30 of about 402. Gmail's estimates are scoped to the selected view. No thread was opened or mailbox state changed during this check. Notify the user in this thread if a fresh Google sign-in is later required.

# Backend foundation scratchpad

## Objective and scope

Implement the Phase 2 Spring Boot/PostgreSQL/Liquibase foundation and local vault API. Bind API only to `127.0.0.1`, validate `Host` and `Origin`, and do not add OAuth or Gmail calls. Own `backend/`, root `compose.yaml`, and this scratchpad only.

## Status

Implementation complete within this workstream. The parent confirmed the active user-approved goal is Phase 2 and is integrating the foundation with the frontend and OAuth/Gmail workstreams.

## Plan and progress

1. Read the technical plan, phase roadmap, current repository state, and Spring Boot requirements.
2. Create Maven/Spring Boot configuration, PostgreSQL Compose service, and Liquibase schema.
3. Implement Argon2id/AES-GCM vault setup/unlock/lock/reset, idle lock, and loopback host/origin protection.
4. Add CSRF-protected activity heartbeat, deny-by-default security, credentialed exact-origin CORS, and session-cookie policy.
5. Perform local configuration/schema checks and record the Maven resolution blocker.

## Files and interfaces

- API contract: `GET /api/v1/vault/status`; `POST /api/v1/vault/setup`; `POST /api/v1/vault/unlock`; `POST /api/v1/vault/lock`; `POST /api/v1/vault/reset`.
- Activity contract: `POST /api/v1/vault/activity` with no sensitive request data; returns 204 when unlocked, otherwise 423. The frontend must include `X-EAST-XSRF-TOKEN` from the `EAST-XSRF-TOKEN` cookie on all state-changing requests.
- Required reset confirmation: `RESET LOCAL DATA`.
- Passphrase policy confirmed with parent: 12–1024 characters.
- Local OAuth callback default, for the future OAuth flow: `http://localhost:8080/login/oauth2/code/google`.
- Environment names follow root setup guidance: `EAST_GOOGLE_CLIENT_ID`, `EAST_GOOGLE_CLIENT_SECRET`, `EAST_GOOGLE_REDIRECT_URI`, `EAST_DB_NAME`, `EAST_DB_USER`, `EAST_DB_PASSWORD`, `EAST_DB_PORT`, and `EAST_SERVER_PORT`. The server bind address is fixed to IPv4 loopback in application configuration and cannot be selected through the environment.
- Spring Boot optionally loads the root `.env` as Java properties from either repository-root or `backend/` working-directory launches; the file remains root-owned, untracked, and is never printed by this workstream.
- Vault provider contract for follow-on settings work: `VaultService.encryptLocal(byte[] plaintext, byte[] associatedData)` returns `EncryptedPayload`; `decryptLocal(EncryptedPayload, byte[] associatedData)` returns plaintext bytes. Both require the unlocked vault and refresh idle activity.
- `ai_provider_configuration` stores only provider/base URL/model metadata and the encrypted API key nonce/ciphertext; it has no plaintext key column.

## Verification

- Host tools discovered: Java 21.0.10, Maven 3.9.13, Docker 28.4.0, Compose 5.1.2.
- `docker compose --file compose.yaml config --quiet` passed using synthetic local validation values; it did not start a container or display credentials.
- A local static validation parsed the master Liquibase XML and confirmed all 15 created application tables appear in the reset allowlist.
- `mvn -DskipTests package` could not resolve `spring-boot-starter-parent:4.1.2`: Maven's default repository is outside the writable workspace. Retrying with `-Dmaven.repo.local=/private/tmp/east-m2` avoided that write but Maven Central DNS/network was unavailable (`Unknown host repo.maven.apache.org`). No compile, migration, database startup, or runtime API check succeeded.
- No tests were added or run.

## Decisions and blockers

- Pin Spring Boot 4.1.2, current stable per Spring's official reference, and PostgreSQL 18.6, current minor per PostgreSQL's official release list.
- Argon2id parameters follow RFC 9106's second recommended profile: 64 MiB, 3 iterations, 4 lanes, 16-byte salt, and 32-byte derived key.
- Persist only the random salt and encrypted verification marker in the vault state row; keep the derived key in process memory and wipe byte arrays on lock.
- Use a 15-minute inactivity window with an injectable UTC `Clock`; the scheduled check wipes the in-memory key. Status polling does not refresh activity; authorized API requests and the explicit heartbeat do.
- CSRF uses a JavaScript-readable `EAST-XSRF-TOKEN` cookie with `X-EAST-XSRF-TOKEN` request header, `SameSite=Strict`, and exact credentialed CORS origins `http://localhost:5173` and `http://localhost:4173`. The server session cookie is `EASTSESSION`, HttpOnly, `SameSite=Lax` to carry session-bound OAuth state on Google's top-level GET callback, local HTTP only, and `IF_REQUIRED` sessions. Origin validation independently rejects untrusted write origins.
- The explicit security chain permits only vault endpoints before app authentication; other `/api/**` requests require authentication and report 401 once CSRF/origin checks pass. OAuth routes are not enabled in this foundation and must be added by the OAuth workstream.
- The changelog uses JDBC/Liquibase directly, enforces a single local app user, mailbox-scoped composite constraints for threads/messages/labels, encrypted content nonce/ciphertext pairs, and seven-day default user cache TTL metadata. Reset truncates only East-owned application tables and preserves Liquibase migration history.
- Parent owns root `.env`, `.env.example`, setup docs, and `docs/project-progress.md`; do not edit those files.

## Handoff

Parent should run one approval-backed Maven compile after the network constraint is addressed, then run migration/API checks against local PostgreSQL. Extend the security allowlist deliberately when the OAuth session flow is implemented. Root owns environment-template docs; OAuth/Gmail client behavior is outside this foundation's scope.

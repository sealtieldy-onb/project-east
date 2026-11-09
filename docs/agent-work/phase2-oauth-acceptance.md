# Phase 2 OAuth and mailbox lifecycle acceptance audit

## Objective and scope

Source-only review of Google OAuth setup, the localhost request boundary, the single local app user, multi-mailbox lifecycle, token storage, ownership checks, and account identity/color handling. The intended current product decision is no routine passphrase or idle lock: East loads its random per-install key from the owner-only local key file. Do not inspect local credentials, `.env`, tokens, browser state, or mailbox content.

## Status

Implementation source and setup documentation are present. Earlier live acceptance documented in `docs/project-progress.md` includes successful Google sign-in and two connected mailboxes; this audit did not repeat those flows. No application code changed. No build, test, browser, OAuth, Gmail, or secret-file access was performed.

## Findings

### Local setup and network boundary — source-complete; runtime configuration not rechecked

- `backend/src/main/resources/application.yaml:30-56` fixes the backend address at `127.0.0.1`, loads the local root `.env` (or parent `.env` when run from `backend/`), and binds the Google callback to the configured backend port.
- `backend/src/main/java/com/east/app/config/LoopbackServerConfiguration.java:14-29` fails startup if the configured address is not exactly `127.0.0.1`, then sets the embedded server address to the IPv4 loopback literal.
- `frontend/vite.config.ts:5-27` binds both dev and preview servers to `127.0.0.1`; its API proxy targets loopback. `compose.yaml:8-11` publishes PostgreSQL only on `127.0.0.1`.
- `GoogleOAuthProperties.java:11-23` enables Google OAuth only when client ID and secret are present and redirect URI exactly matches `http://localhost:<server-port>/login/oauth2/code/google`. Its `toString()` reports only configured/match booleans, not credential values.
- `docs/google-oauth-setup.md:12-22,24-43` gives the exact localhost callback, instructs the user to protect `.env` with `chmod 600`, notes the matching configured port, and keeps the AI key in East settings. `.gitignore:16-30` ignores `.env` and credential/key files; `.env.example:1-22` contains placeholders only. This audit intentionally did not inspect `.env` or verify its present mode.
- `LocalRequestBoundaryFilter.java:20-64,69-101` rejects non-local Host values, restricts request origins to the local Vite origins, and permits cross-site navigation only for the exact OAuth callback. `LocalSecurityConfiguration.java:64-100` uses session-backed authentication, CSRF on writes, an HTTP-only SameSite=Lax session cookie for Google's top-level callback, and protected API routes.

### Google sign-in and Gmail linking — source-complete; prior runtime acceptance recorded

- `GoogleOAuthService.java:23-27,52-64,143-164` separates sign-in (`openid email profile`) from explicit mailbox linking (adds `gmail.modify`, `access_type=offline`, account selection, and consent). Gmail access is not requested at app sign-in.
- `OAuthSessionStateStore.java:19-70` creates independent 32-byte random state and nonce values, stores pending intent and owner ID in the server session, expires them after ten minutes, caps pending flows, and consumes state once.
- `GoogleOAuthConfiguration.java:23-37,40-74` configures RS256 verification against Google's JWK endpoint and validates issuer, expiration, audience, and authorized party. `GoogleOAuthService.java:66-103,120-140` checks granted scopes, token expiry/algorithm, nonce, non-empty subject/email, and verified email.
- `GoogleOAuthCallbackController.java:49-63` anchors app sign-in through the authenticated Google subject; for mailbox linking it requires the currently signed-in session and checks that its app-user ID matches the server-stored pending intent before connecting the returned identity.
- `AuthRepository.java:22-53,67-75` enforces one anchored local app user and rejects a different Google sign-in subject. `001-initial-local-schema.sql:11-14,22-44` enforces a singleton app user and permits multiple mailbox rows with distinct Google subjects for that user.
- Earlier progress entries record successful sign-in and two mailbox connections. Live reauthorization/reconnect after a revoked grant remains a separate runtime workflow and was not exercised in this source-only audit.

### Mailbox ownership, tokens, colors, and disconnect — source-complete

- `MailboxController.java:36-55` obtains the current local user for mailbox list, connect, and disconnect. `MailboxRepository.java:47-63,65-103,178-182` scopes identity lookup, mailbox lookup, listing, and delete by `app_user_id` plus mailbox ID/Google subject.
- `MailboxService.java:32-95` encrypts refresh tokens through the vault with mailbox-specific AAD, preserves the existing token when Google omits a replacement during a healthy relink, and wipes temporary byte arrays. `GoogleAccessTokenService.java:42-109,112-139` requires mailbox ownership and `gmail.modify`, caches short-lived access tokens only in memory, and clears them after invalidation; `MailboxTokenAad.java:11-15` binds ciphertext to the app user, mailbox, Google subject, and token purpose.
- New mailbox colors begin from a deterministic Google-subject hash and probe unused indices while holding a lock on the app-user row (`MailboxService.java:32-46`; `MailboxRepository.java:37-45,105-119`). Existing connections preserve their index. `004-account-color-capacity.sql:1-12` widens the persisted index beyond eight values, and `frontend/src/lib/mailbox-colors.ts:1-32` maps stored indices to the first eight Nord swatches and then a deterministic non-wrapping sequence. The account’s name/email remain text in `ConnectedDashboard.tsx:63-65`; color is not the only identity cue.
- `MailboxService.java:103-112` requires an authenticated owner at the controller boundary, deletes local report/source data and the owner-scoped mailbox row (whose dependent records use database cascades), then invalidates its cached access token. It does not call Google's revoke endpoint. `ConnectedDashboard.tsx:99-110` confirms the local purge and tells the user Google may retain the grant; this matches the decision recorded in `docs/decisions-and-open-questions.md:66-67,73-74`.

## Acceptance assessment

| Requirement | Assessment | Evidence |
|---|---|---|
| Loopback-only local service and OAuth callback setup | Source/docs complete; live sign-in has succeeded previously | `LoopbackServerConfiguration.java`, `LocalRequestBoundaryFilter.java`, `vite.config.ts`, `google-oauth-setup.md` |
| Protected OAuth client config, excluded from source control | Documented and ignored; this audit did not read or stat the local secret file | `.gitignore`, `.env.example`, `google-oauth-setup.md` |
| Generic sign-in plus separately authorized mailbox linking | Source complete; prior live sign-in and two-account connection recorded | `GoogleOAuthService.java`, `GoogleOAuthCallbackController.java`, `project-progress.md` |
| One local app user, multiple owned mailboxes; no hardcoded account identities | Source/schema complete | `AuthRepository.java`, `MailboxRepository.java`, migration 001 |
| Encrypted refresh tokens and access-token invalidation | Source complete; not re-tested | `MailboxService.java`, `GoogleAccessTokenService.java` |
| Stable distinct account color and visible account identity | Source/schema complete; not re-tested visually in this audit | `MailboxRepository.java`, migration 004, `mailbox-colors.ts`, `ConnectedDashboard.tsx` |
| Local-only disconnect and user disclosure | Source complete; no live disconnect was performed | `MailboxController.java`, `MailboxService.java`, `ConnectedDashboard.tsx` |

## Gaps and next step

No OAuth/mailbox-lifecycle code defect was identified by this audit. Runtime evidence remains the earlier user-confirmed sign-in/two-account connection; a later end-to-end OAuth reconnect and disconnect flow has not been verified in this workstream. The local `.env` was deliberately not read or stat-ed here. Continue live mailbox-action acceptance only after the user completes the pending read/unread clicks; do not disconnect/revoke an account for acceptance without a specific user-directed test.

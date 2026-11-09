# Google OIDC and mailbox-linking scratchpad

## Objective and scope

Implement optional Google OIDC app sign-in and explicit multi-mailbox OAuth linking under `backend/src/main/java/com/east/app/auth/`, with only required endpoint/security matcher additions in `LocalSecurityConfiguration.java`. The same callback handles both flows through session-bound one-time state. Do not edit Liquibase migrations, frontend, Gmail REST/client workflows, shared progress, or environment files. Never inspect or print `.env`; never contact Google or launch browser authorization during development.

## Status

Implementation source is in place; awaiting the parent agent's coordinated compile and final review. No live Google authorization, Google API call, or credential inspection was performed.

## Plan and progress

1. Add optional config/status and session-bound OAuth request storage, with separate sign-in/link intents.
2. Exchange authorization codes server-side and validate Google ID-token signature/JWKS, issuer, audience, expiration, nonce, subject, and verified email.
3. Persist the single anchored app user and owned mailbox profiles; encrypt refresh tokens with mailbox-specific AAD and preserve existing tokens when omitted.
4. Add `/me`, mailbox list/connect/disconnect, and local logout endpoints; enforce authenticated session, vault unlock, and ownership.
5. Update security matchers and verify source statically. Do not compile concurrently with the parent.

## Contract and decisions

- Redirect URI: `http://localhost:8080/login/oauth2/code/google`.
- Sign-in scopes: `openid email profile`; mailbox-link scopes add `https://www.googleapis.com/auth/gmail.modify` and request `access_type=offline`, `prompt=select_account consent`.
- State and nonce are random, one-time, stored in the server-side session, and expire after ten minutes. Link intent stores the current app-user ID in session; no target user ID is accepted from the browser.
- First OIDC subject anchors the one local app user. A different subject is rejected until local reset.
- Refresh tokens are encrypted via `VaultService.encryptLocal` with AAD scoped to app-user and mailbox; only encrypted bytes are stored. Existing refresh tokens survive responses that omit a replacement.
- The session cookie is `SameSite=Lax` so Google's top-level GET callback can carry session state. CSRF remains enabled for API writes; the CSRF cookie remains `SameSite=Strict`.

## API contracts and behavior

- `GET /api/v1/auth/configuration`: `200` JSON boolean (`true`/`false` only). Returns `423` while the vault is locked due to the local API gate.
- `GET /api/v1/auth/google/start`: `302` with `Location` set to the Google authorization URL; callback lands at the fixed localhost URL. If OAuth credentials are not configured, returns `503` Problem Detail.
- `GET /login/oauth2/code/google`: successful sign-in or mailbox linking returns `303 Location: http://localhost:5173/`; no tokens or account data are placed in the redirect. OAuth denial, invalid/expired state, rejected scopes, or token/identity validation failures return an appropriate `400`, `403`, or `502` Problem Detail. A different sign-in subject gets `409`.
- `GET /api/v1/me`: `200` JSON `{ "id": UUID, "email": string, "displayName": string|null, "mailboxes": MailboxSummary[] }`; `401` if not signed in and `423` if locked.
- `GET /api/v1/mailboxes`: `200` JSON `{ "items": MailboxSummary[] }`; `401` if not signed in and `423` if locked.
- `MailboxSummary`: `{ "id": UUID, "email": string, "displayName": string|null, "colorIndex": number, "lastSyncAt": ISO-8601 timestamp|null }`. No OAuth credential is included.
- `POST /api/v1/mailboxes/connect`: requires authenticated session, unlocked vault, and CSRF token; returns `200` JSON `{ "authorizationUrl": string }`. Missing OAuth config returns `503`; browser navigation to this URL starts the mailbox-link flow. The intended user ID comes from the server-side principal and is stored in one-time session state.
- `POST /api/v1/auth/logout`: requires CSRF protection; returns `204` and invalidates the local session. The vault lock gate may return `423` first.
- `DELETE /api/v1/mailboxes/{mailboxId}`: authenticated, unlocked, CSRF-protected local-only purge; `204` on success or `404` for absent/unowned mailbox; no Gmail revoke or mailbox mutation is issued.
- Auth/API failures use Spring Problem Detail JSON `{ "type": URI, "title": string, "status": number, "detail": string }`; Spring Security's unauthenticated entry point returns `401`.

## Security behavior

- `EASTSESSION` is HTTP-only and `SameSite=Lax` so the top-level Google callback can carry the session-bound state. CSRF cookie/header are `EAST-XSRF-TOKEN` / `X-EAST-XSRF-TOKEN`; API writes remain CSRF protected.
- `LocalRequestBoundaryFilter` restricts Host to `localhost` or `127.0.0.1` on the configured backend port and restricts browser origins to the configured local Vite origins. Only the exact Google callback GET accepts the expected cross-site navigation.
- Security matchers permit only vault bootstrap endpoints, auth configuration/start, logout, and the exact OAuth callback before app authentication. All other `/api/**` endpoints require an authenticated session. The post-authorization vault filter returns `423` for non-vault APIs while locked.
- OIDC validation is delegated to a JWKS-backed Nimbus decoder (RS256, Google issuer, audience and authorized-party checks), then checks expiration, nonce, non-empty stable subject, and `email_verified=true`. OAuth `state` and nonce are cryptographically random, one-time, session-bound, and expire after ten minutes.
- Refresh token encryption uses mailbox-specific AAD. The `EncryptedPayload` backing nonce/ciphertext is destroyed after copying bytes for DB persistence or after decryption; temporary byte arrays are wiped. Cached access tokens are held in mutable char arrays, destroyed on invalidation/expiry, and cleared by a one-second scheduler that checks `VaultService.status()` without refreshing activity. Access is gated by `requireUnlocked()` and `decryptLocalWithoutTouch()`; foreground API activity is recorded at the request boundary so scheduled token refresh never extends the human idle timer.

## Verification

- Source review only. No build, tests, browser authorization, or Google network call has been run. Parent requested no concurrent compile and will coordinate verification.

## Handoff

Parent owns final Maven verification and the shared progress log. Changed files are the `backend/src/main/java/com/east/app/auth/` OAuth/session/mailbox sources, `backend/src/main/java/com/east/app/config/LocalSecurityConfiguration.java`, and this scratchpad. No Liquibase schema changes were required. Exposed internal token access is `GoogleAccessTokenService#getValidAccessToken(UUID appUserId, UUID mailboxId)`, requiring ownership and an unlocked vault; it returns a short-lived access token only to backend Gmail services. `invalidate(UUID mailboxId)` clears a mailbox's cached token.

# Backend availability feedback

Status: implementation complete; independent re-review pending on `fix/backend-status-recovery`, based on local `main` at `fa56574`.

## Report and diagnosis

The frontend can show a local-backend-unavailable message after a transient request failure even after Spring Boot is available. The initial vault status request sets `vaultMode` to `unavailable`; the existing focus/visibility refresh effect is installed only when `vaultMode` is `unlocked`, leaving no automatic recovery path from the unavailable state.

Vite's proxy fallback is `127.0.0.1:8080`. The local East configuration uses numeric `EAST_SERVER_PORT=8081`, so a frontend started without `EAST_API_TARGET` can send API calls to the wrong local service. The configured port was confirmed without reading or recording any other `.env` values.

The initial `/api/v1/auth/configuration` and `/api/v1/me` checks also ran only once. A transient failure could leave a sign-in notice stuck while vault status remained healthy; the existing 30-second session check does not retry for a signed-out user.

## Change

- Retry vault status while the page is visible and the vault remains unavailable; retry immediately on focus or visibility restoration.
- Deduplicate concurrent status reads so a delayed request cannot overwrite a newer result.
- Preserve an active fictional demo session while the local service recovers or reports a migration state.
- Retry only failed auth configuration/session checks while the page is visible. A failed configuration check can recover even after `/api/v1/me` returns a signed-in user; failed user checks stop once a signed-in user is available. Sign-out still triggers the existing auth recheck. Clear only an auth notice created by these checks after recovery.
- Let Vite honor `EAST_API_TARGET`; otherwise derive a numeric port from the Vite process environment or only `EAST_SERVER_PORT` in the root `.env`, falling back to 8080.
- Reject an explicit Vite target unless it is a plain HTTP loopback URL, so same-origin session cookies are not proxied to external hosts.
- Update local run instructions to describe the proxy target selection.

## Verification

- Parent verified both local listeners on `127.0.0.1:8081` and `127.0.0.1:5173` and confirmed the configured backend port is 8081.
- `npm ci --offline` installed 42 locked frontend packages from the local cache. The frontend production build passed after the review fixes: TypeScript succeeded and Vite transformed 2,083 modules. Vite reported one output chunk over 500 kB. No tests or browser-flow checks were run.
- `git diff --check` passed after the review fixes. A fresh independent review found and blocked on the partial-auth retry edge; that was fixed and returned to the same reviewer, who passed the updated diff. The reviewer also confirmed the follow-up clarification for rejected query strings and fragments. Service restart remains pending.
- No credentials, tokens, mailbox data, or other `.env` values were accessed or recorded.

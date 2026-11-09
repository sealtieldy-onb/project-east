# Phase 2 frontend integration scratchpad

## Objective and scope

Add a frontend vault setup/unlock gate backed by `/api/v1/vault/*` while preserving East's fictional demo when the local backend is unavailable or Gmail is not configured. Frontend files only, plus this scratchpad. No credentials or real mailbox data.

## Status

Complete. Existing React/Vite demo is preserved and integrated with the local vault contract, including throttled backend activity refresh.

## Plan and progress

1. Added a typed, same-origin `/api` vault client and isolated setup/unlock/reset component.
2. Integrated startup status, locked/unlocked state, demo fallback, explicit lock, idle-lock behavior, and throttled activity refresh into `App.tsx`.
3. Added a Vite proxy to `http://localhost:8080`, with the app bound to `http://localhost:5173` to preserve the configured browser origin.
4. Ran the production build successfully.

## Files and interfaces

- Existing app: `frontend/src/App.tsx`, `frontend/src/components/LoginPage.tsx`, `frontend/src/index.css`.
- New integration: `frontend/src/lib/vault-api.ts`, `frontend/src/components/VaultGate.tsx`, `frontend/src/components/vault-gate.css`.
- Local API forwarding: `frontend/vite.config.ts`; start command: `frontend/package.json`.
- Vault contract: GET status `{ initialized, unlocked, idleTimeoutMinutes }`; setup `{ passphrase, confirmPassphrase }`; unlock `{ passphrase }`; lock `{}`; reset `{ confirmation }`; activity is a payload-free POST.

## Verification

- `npm run build` in `frontend/` passed after final changes: TypeScript project build and Vite production bundling completed with exit code 0 (2065 modules transformed).
- Rebuilt after the exact `RESET LOCAL DATA` confirmation correction; the production build passed (2065 modules transformed, exit code 0).
- Static source search found no `localStorage`, `sessionStorage`, or console logging in the vault integration path.
- No API runtime/backend integration check was run.

## Decisions and blockers

- Passphrases exist only in component memory and request bodies; never use local/session storage, logs, or URL parameters. Setup enforces 12–1,024 characters per parent-provided backend contract.
- Destructive reset requires the exact typed phrase `RESET LOCAL DATA`, matching the backend contract.
- User interaction refreshes backend activity at most once per minute while unlocked. Mouse movement shares the same throttle; activity failures are ignored so they do not affect demo access or vault state.
- If status cannot be fetched, keep the existing demo login available and clearly label the backend as unavailable.
- If the vault is unlocked but no Gmail integration is configured, retain the fictional demo path; do not replace it with empty mailbox states.
- No blockers known.

## Handoff

Handoff is ready. The parent can integrate the frontend-only vault contract once the backend endpoints are available. Do not edit the shared project progress log.

# Settings UI scratchpad

## Objective and scope

Add an accessible settings dialog and typed API client for AI-provider configuration and message/attachment cache retention, integrated into the authenticated application shell. A shared CSRF helper supplies the backend's `EAST-XSRF-TOKEN` cookie as `X-EAST-XSRF-TOKEN` on unsafe same-origin API methods in both settings and vault clients; it never logs the token.

## Status

Implementation complete and integrated in the authenticated app shell. The current frontend build passed on 2026-11-09: TypeScript project build and Vite production bundle completed with 2,078 modules transformed. The settings dialog, typed settings client, and shared CSRF helper are present.

## Plan and progress

1. Defined the typed API surface for AI provider and retention routes.
2. Built an accessible native-dialog UI with OpenAI/custom compatible endpoint and retention controls.
3. Added same-origin API key submission, immediate input clearing, and the reusable CSRF cookie/header helper.
4. Ran the production build successfully.

## API shape assumptions

- `GET /api/v1/settings/ai-provider` returns `{ provider: "openai" | "openai-compatible" | null, baseUrl: string | null, model: string | null, keyConfigured: boolean }`.
- `PUT /api/v1/settings/ai-provider` accepts `{ provider, baseUrl?, model, apiKey? }`. Omission of `apiKey` preserves the saved key; no response body is required because the client refreshes with GET.
- `DELETE /api/v1/settings/ai-provider` removes provider metadata and the key.
- `GET /api/v1/settings/retention` returns `{ bodyCacheDays: number }`; `PATCH` accepts the same shape and needs no response body because the client refreshes with GET.

The JSON contract is aligned with `SettingsController` and `SettingsService`.

## Files and interfaces

- `frontend/src/components/SettingsDialog.tsx` — default export accepting `{ onClose }`.
- `frontend/src/components/settings-dialog.css` — isolated settings styles.
- `frontend/src/lib/settings-api.ts` — same-origin GET/PUT/DELETE/PATCH client and response types.
- `frontend/src/lib/csrf.ts` — reads the backend CSRF cookie and returns the matching request header; reused by settings and vault clients.
- `frontend/src/lib/vault-api.ts` — adds the shared CSRF header to POST requests (necessary to satisfy the backend security contract).

## Verification

- Latest `npm run build` in `frontend/` — passed on 2026-11-09 (`tsc -b` and `vite build`; 2,078 modules transformed).
- No model-provider API calls are part of this work.

## Decisions and blockers

- Cache retention input matches the Liquibase `body_cache_ttl_days` constraint: whole days from 1 through 365; the API-provided seven-day value remains the default.
- Never fetch, render, log, or persist a saved API key. Only send an explicitly entered key in PUT; clear the field before awaiting the request.
- Show only the server's `keyConfigured` boolean.
- Server error bodies are not rendered, to avoid echoing submitted credentials.
- Live settings load/save/removal and retention edits remain unverified because the current browser is signed out. Do not enter or invent an AI-provider API key as part of acceptance; any key must come from the user and is sent only to the local backend.

## Handoff

`frontend/src/App.tsx` opens the dialog from the authenticated shell; the fictional demo path remains separate. Runtime settings acceptance requires the user to sign in and choose whether to provide their own AI-provider key.

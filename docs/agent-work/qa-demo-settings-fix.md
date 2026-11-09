# Demo Settings unauthenticated QA fix

## Scope

- Added a demo-specific Settings dialog that explains personal settings require Google sign-in, states the fictional demo does not load or save those settings, and provides a route back to sign-in.
- Wired the dialog only for sessions without a signed-in Google user. Signed-in sessions continue using the existing `SettingsDialog` and its settings APIs.
- The demo dialog imports no settings/auth API client and performs no settings requests on open.

## Checks and results

- `git diff --check` — passed.
- `rg -n 'getAIProviderSettings|getRetentionSettings|patchRetentionSettings|getDailyReportSettings|fetch\(' frontend/src/components/DemoSettingsDialog.tsx` — no matches; verified the demo component does not call settings APIs or `fetch`.
- `npm ci --offline` in `frontend/` — passed using the local npm cache; installed the lockfile dependencies (42 packages, 0 vulnerabilities).
- `npm run build` in `frontend/` — passed; TypeScript project build and Vite production build completed (2,081 modules transformed).
- Automated test scripts — none are defined in `frontend/package.json`; no separate test suite was run.

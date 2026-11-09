# Demo sync status consistency

Date: 2026-11-10

## Scope

Fix the QA P3 finding where the fictional demo Dashboard said “Example data · not synced” and “Last checked just now.” Keep the demo explicitly fictional and avoid implying a live mailbox check occurred.

## Change

- Replaced the footer timestamp claim with “No mailbox sync has run.” The summary and footer now both describe the demo as unsynced.

## Verification

- `npm ci --offline --no-audit --no-fund`: passed (lockfile dependencies were available in the local cache).
- `npm run build`: passed (`tsc -b`; Vite transformed 2,081 modules).
- `git diff --check`: passed.
- No Gmail, app runtime, or database was used.

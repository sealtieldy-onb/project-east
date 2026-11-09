# Settings API workstream scratchpad

Updated: 2026-11-09

## Goal

Implement Phase 2 local settings endpoints backing the existing React settings dialog. Keep model keys encrypted by the unlocked vault, never return keys, and persist the user-configurable 1–365 day body-cache retention value.

## Current implementation

- Added `backend/src/main/java/com/east/app/settings/` DTOs, controller, service, and exception handling.
- `GET/PUT/DELETE /api/v1/settings/ai-provider` reads or writes provider metadata and ciphertext only. API-key input is converted to bytes, encrypted with vault AES-GCM and fixed AAD, and wiped after use; blank keys preserve the existing encrypted key and cannot create a keyless provider record.
- Provider values map from the UI's `openai` / `openai-compatible` to database enum strings. Compatible endpoints require HTTPS or localhost HTTP and reject URL userinfo, query, and fragment.
- `GET/PATCH /api/v1/settings/retention` uses `app_user.body_cache_ttl_days`, range 1–365. Settings operations that write check the vault is unlocked. Retention updates require the anchored Google app user to exist.
- Changing retention recalculates expiry timestamps for still-live encrypted body, snippet, and fetched-attachment caches, plus source-linked insights and their summary runs. Already-expired rows are not extended; the periodic cleanup removes them.
- No AI calls are made by this workstream.

## Verification

- `mvn -o -B -f backend/pom.xml -DskipTests compile` passed on 2026-11-09; classes were current and all 113 Java sources had compiled in the prior build.
- The configured PostgreSQL database is healthy and reports Liquibase changesets 001–008 applied. `docker compose config --quiet` passes; clean temporary-database migration verification currently ends at 007.
- Settings endpoints have not been exercised from the authenticated browser after the latest backend restart; the current browser session is signed out. No model API key was entered and no model API was called.

## Follow-up acceptance

- Live-check settings load/save/removal and retention changes after user sign-in. Retention changes affect expiry timestamps, so the user must choose the value and submit it in East.
- The frontend settings dialog also enforces 1–365 days; the current production build passes.
- No AI calls are made by this workstream. The API-key field remains empty until the user elects to configure a provider and supplies their own key.

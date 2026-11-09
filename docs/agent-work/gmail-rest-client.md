# Gmail REST client scratchpad

## Objective and scope

Add an isolated Spring `RestClient` wrapper for Gmail API v1 under `backend/src/main/java/com/east/app/gmail/`. It accepts an access token per call and does not persist or log tokens, email content, recipients, or attachment bytes.

## Status

Source review complete. Awaiting backend-foundation compile signal. Last checkpoint: 2026-11-09.

## Plan and progress

- Confirmed `spring-boot-starter-web` supplies Spring `RestClient`; Java source root is `com.east.app`.
- Added profile, message/thread/draft list and detail, labels, label modifications, trash/untrash, batch modify, history, attachment bytes, draft lifecycle, and message/draft send methods.
- Added URL-builder-based path/query construction and sanitized HTTP error mapping.
- Added drafts list/detail after confirming the current Gmail API parameters in Google's REST reference.

## Files and interfaces

- `backend/src/main/java/com/east/app/gmail/GmailApiClient.java` is the only production source owned here.
- Read responses use Jackson `JsonNode`; Gmail resource request bodies use `Map<String, Object>`.
- `GmailApiClient.GmailApiException` exposes only HTTP status and a sanitized Google error code.

## Verification

- No Gmail API requests made.
- Maven compile not run yet; parent asked to wait for backend foundation signal and coordinate Maven builds.
- Draft list/detail signatures match Google's current reference: list supports `q`, `maxResults`, `pageToken`, and `includeSpamTrash`; get supports `format`.
- Reviewed source for endpoint coverage, per-call token handling, sanitized error output, and absence of retry/logging behavior; no obvious compile concern found, but compilation is still unverified.

## Decisions and blockers

- The client remains intentionally independent from vault/auth code through the per-call access-token contract.
- Client has no automatic retry path; send and other mutations are single HTTP attempts.

## Handoff

Wait for the parent's explicit backend-foundation compile signal. Then coordinate to run one Maven compile from `backend/`; report its actual result. Do not run any live Gmail authorization or API call.

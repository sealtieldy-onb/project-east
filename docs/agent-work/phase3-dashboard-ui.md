# Phase 3 Dashboard report UX

Date: 2026-11-09
Status: frontend manual report flow, opt-in daily-report settings, and AI-assisted local draft generation implemented against the Phase 3 API contract. The expanded-read wording now states the aggregate scope and 240-body cap.

## Changes

- Replaced the Phase 3 placeholder with report period and mailbox scope controls, saved report loading, provider/model details, explicit content/provider disclosure, a manual Generate action, and a separate 30-message expansion decision.
- Default the Dashboard to the past seven days, but select the past 30 days when the server recommends a first report and the user has not manually changed the range. The user still starts the count preview and report explicitly. Dashboard reads only call local read APIs and never invoke model processing.
- Added an explicit two-step manual flow: first request a header-only eligible count, then show provider/model/destination, date/account scope, and content disclosure with a confirmation checkbox. The confirmed request carries both preview counts; changed counts require renewed confirmation before any body fetch.
- The separate expansion prompt states the aggregate number across selected accounts and that an approved run fetches at most 240 additional bodies. Any remainder stays unread and is reflected in partial coverage.
- Added typed report response parsing for the Dashboard response, `COMPLETED` generation response, `EXPANSION_REQUIRED`, four insight kinds, confidence, coverage, transaction details, and source references.
- Added insight cards with account labels, confidence, partial coverage, transaction fields, and source-message links. Links dispatch `east:openConversationSource` with mailbox, thread, and message IDs.
- Added an App event bridge and Conversations navigation that selects the owning mailbox, opens the source thread, and scrolls/focuses the cited message. Ownership remains checked by the existing mail-read endpoint.
- Re-reads provider configuration immediately before each generation request. If the provider, endpoint, or model changed since disclosure, East clears confirmation and requires the user to review the new destination.
- Disclosure names the provider/model, date range, mailbox scope, eligible message count, and attachment exclusion. OpenAI links to its privacy policy; custom endpoint UI identifies its configured URL and states that its operator controls processing and retention.
- Added an opt-in daily-report schedule in Settings, off by default, with local run time and IANA time zone. It discloses the current provider/model/destination, daily email-text processing, attachment exclusion, OpenAI privacy terms or custom endpoint operator terms, and the one-day catch-up behavior.
- The daily-settings client supports GET/PUT on `/api/v1/reports/daily/settings`. Provider/model changes or the server's `providerDisclosureRequired` response pause the schedule and require a new checkbox confirmation before it can be enabled again. The save action re-reads the current provider configuration before enabling.
- Daily schedule enablement also includes the currently disclosed provider configuration fingerprint; disabled schedule updates send no provider confirmation token.
- Added a composer-only AI draft panel for new, reply, and reply-all drafts. It reads provider settings only after the user opens the panel, displays provider/model/destination, discloses email-text and instruction processing and attachment exclusion, and requires a checkbox before the request. It re-reads settings before submission and sends the opaque provider configuration fingerprint so the backend can reject changes that race the user's confirmation.
- The assist request creates a separate encrypted local draft. East persists the current editable local draft first, then opens the generated result for editing and keeps the prior draft available. The UI leaves Save to Gmail and Send as separate existing actions; it does not call either from the assist flow. Plain-text assistant output is rendered in the editor when no rich-text body is returned.
- Added the same disclosed provider configuration fingerprint to manual report generation requests, including the expansion decision, and re-confirmation is required when that fingerprint changes.
- Daily report results use the schedule's local report date; the dashboard can include them when filtering that date range.

## Files

- `frontend/src/components/ConnectedDashboard.tsx`
- `frontend/src/components/connected-dashboard.css`
- `frontend/src/components/Conversations.tsx`
- `frontend/src/components/conversations.css`
- `frontend/src/App.tsx`
- `frontend/src/lib/reports-api.ts`
- `frontend/src/lib/daily-report-settings-api.ts`
- `frontend/src/components/SettingsDialog.tsx`
- `frontend/src/components/settings-dialog.css`
- `frontend/src/components/Composer.tsx`
- `frontend/src/components/composer.css`
- `frontend/src/lib/drafts-api.ts`
- `frontend/src/lib/settings-api.ts`

## Checks

- Final `npm run build` after report-approval wording, assistant-draft, manual-report, and daily-settings changes: passed (`tsc -b` and Vite production build; 2,080 modules transformed).
- Final `git diff --check` passed after the documentation updates.
- No tests or live Gmail/provider requests were run.

## API assumptions

- Dashboard returns `aiConfigured`, date bounds, coverage counts, optional `{ id, status, generatedAt, narrative }` summary, and insights with flat `transactionType`, `amount`, `currency`, and `transactionDate` fields.
- Coverage status may be `UNKNOWN` with zero counts when no report exists. The parser accepts it and the UI shows coverage text only when a summary exists and coverage is known.
- Generation returns `DISCLOSURE_REQUIRED` for the header-only preview or changed preview counts; `EXPANSION_REQUIRED` includes `initialLimit`, `additionalMessageCount`, and `maxAdditionalMessageCount` (up to 240); completed responses include `summary`, `insights`, and `coverage`.
- Source references contain `mailboxId`, `threadId`, `messageId`, and optionally `accountEmail`.
- Daily settings GET returns `{ enabled, runAtLocal, timezoneId, providerDisclosureRequired }`; PUT accepts those schedule values plus `providerDisclosureConfirmed` and returns the same settings shape. `runAtLocal` may use `HH:mm` or `HH:mm:ss`.
- A custom endpoint does not provide a separate policy URL in the existing settings API. The UI discloses that operator terms apply and links to the configured endpoint as the available starting point; it does not assert that endpoint page is a policy document.
- GET `/api/v1/settings/ai-provider` returns `configurationVersion` as an opaque safe fingerprint when configured and `null` when unconfigured. Draft assist and manual report generation POSTs send it as `providerConfigurationVersion`; daily schedule updates send it when enabling. The backend checks it before processing content and before provider transmission.
- POST `/api/v1/drafts/assist` returns the existing draft response shape. The frontend sends the current recipient and optional subject fields for local draft construction; instructions and, for replies, selected source message text/context are disclosed as provider-bound content. No attachment contents are sent.

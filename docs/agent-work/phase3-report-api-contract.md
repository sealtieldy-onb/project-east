# Phase 3 report API contract

Date: 2026-11-10
Status: implemented for the current Phase 3 backend and frontend paths; three independent review findings were fixed, synthetic report/daily/draft acceptance passed, and changeset 009 was applied and database-verified after user authorization. Adjust only with a documented product reason.

## Guardrails

- A saved AI key is configuration only. Report processing starts only from an explicit Generate report click or an enabled daily schedule.
- Before a manual report, the UI names the configured provider/model/destination, date range, mailbox scope, current eligible-message count, and that text email content will leave East. A separate explicit header-only count preview never fetches bodies or calls the provider. The user then confirms the complete disclosure and submits the non-secret provider-configuration fingerprint. The server checks that fingerprint before any report body read and again before provider transmission.
- The first-link report uses the past 30 days. The server applies the 30-message initial allowance per selected mailbox and calculates the additional count across the selected mailboxes. If it is nonzero, the server returns that count before fetching any bodies. A separate confirmed request is required to fetch beyond the allowance; one manual report can read at most 240 additional bodies, a cap shown in the approval prompt. A declined expansion or a cap that leaves eligible messages unread produces partial coverage.
- Daily reports use the previous local calendar day in the opted-in schedule timezone. The scheduler never treats the schedule as consent to expanded Gmail reads; it uses unexpired local cache only, selects at most 240 bodies globally with fair per-mailbox ordering, and reports partial coverage when capped or source bodies are unavailable.
- No attachment bytes or extracted attachment contents enter model prompts. Model output is treated as untrusted and cannot trigger Gmail actions.
- Every message body sent to a model is recorded in `summary_run_source`; every insight claim records its supporting message IDs. Persistence and provenance writes happen in one transaction. Reports and insights expire with their source bodies and are purged on mailbox disconnect when any source mailbox is disconnected.

## Routes

- `GET /api/v1/dashboard?periodStart=YYYY-MM-DD&periodEnd=YYYY-MM-DD&timezoneId=...&mailboxId=...` reads the latest report for the requested date/scope and whether AI is configured. The local UI sends its IANA timezone so date validation and day boundaries match its calendar; omitted dates default to the past seven days, and older clients that omit `timezoneId` use UTC. A scheduled report is matched by its configured local report date. A missing report never causes a model call.
- `POST /api/v1/dashboard/summaries` accepts `{ periodStart, periodEnd, timezoneId?, mailboxIds?, providerDisclosureConfirmed, providerConfigurationVersion, expectedEligibleMessageCount?, expectedAdditionalMessageCount?, expandedReadDecision? }`, where the decision is `ALLOW`, `DECLINE`, or omitted/`NOT_ASKED`. Date-only bounds are interpreted at local midnight in `timezoneId`; omission retains UTC behavior. Empty or omitted `mailboxIds` means all currently connected mailboxes. A request with `providerDisclosureConfirmed: false` returns `DISCLOSURE_REQUIRED` and current header counts without reading bodies or calling the provider. Confirmed requests repeat the preview counts; if they changed, the server returns updated `DISCLOSURE_REQUIRED` counts and requires confirmation again. The 30-message baseline applies per selected mailbox. If any selected mailbox has more than 30 eligible messages and no expansion decision was supplied, the server returns `EXPANSION_REQUIRED` and an aggregate `additionalMessageCount`; it must not fetch message bodies or call the provider. After the user chooses whether to allow expansion, the UI repeats with `expandedReadDecision: ALLOW` or `DECLINE`.
- `GET /api/v1/reports/daily/settings` returns `{ enabled, runAtLocal, timezoneId, providerDisclosureRequired }`.
- `PUT /api/v1/reports/daily/settings` accepts `{ enabled, runAtLocal, timezoneId, providerDisclosureConfirmed, providerConfigurationVersion }`. The schedule is disabled by default. Store the disclosed provider configuration version. If the provider or model changes afterward, pause scheduled generation and require renewed disclosure before resuming.
- `POST /api/v1/drafts/assist` accepts an explicit draft type, scoped mailbox/source, instructions, provider disclosure confirmation, and `providerConfigurationVersion`. Reply prompts contain source message text and headers only; attachments are excluded. The result is a separate encrypted local draft. Gmail save and send remain separate user actions.

All routes resolve the local app user from the session. Explicit mailbox IDs must be connected and owned by that user. Date bounds are validated and message selection is bounded by the report policy and provider-client limits.

## Response shape

Dashboard responses return:

- `aiConfigured` without exposing any key;
- `periodStart`, `periodEnd`, and `coverage` (`COMPLETE` or `PARTIAL`), including eligible/selected/additional message counts;
- an optional latest `summary` with `id`, `status`, `generatedAt`, and one concise narrative paragraph;
- typed `insights[]` for `URGENT_ACTION`, `AWAITING_REPLY`, `REPLY_RECEIVED`, and `TRANSACTION`, each with confidence, display text, optional urgency/deadline/transaction fields, and `sources[]` containing owned mailbox/thread/message IDs.

Model output source IDs must be a subset of the exact input IDs. The server rejects unknown IDs, malformed amounts/dates, out-of-range confidence, unsupported enums, excessive field lengths, and claims with no source evidence. Unknown transaction values remain null rather than guessed.

Generation may return `DISCLOSURE_REQUIRED` with current coverage counts and `initialLimit`, or `EXPANSION_REQUIRED` with `initialLimit`, `additionalMessageCount`, and `maxAdditionalMessageCount` (at most 240). Neither response contains generated content. The user may approve the bounded additional read or decline it.

## Persistence direction

Liquibase changeset 009 is registered after 008 and remains unapplied. Preserve migrations 001–008. The changeset extends `summary_run` with purpose, coverage/counts, provider/model and prompt/ruleset identifiers, and scheduled-period idempotency; it also adds `summary_run_mailbox` so selected scope is explicit even when a mailbox contributes no eligible message. Keep report narrative and insight content encrypted. Do not persist prompts, raw email bodies, API responses, provider error bodies, or API keys in report rows.

## Remaining implementation sequence

1. Changeset 009 is applied to the configured East database. A disposable PostgreSQL run applied all nine changesets and passed database-backed retention and disconnect checks with synthetic rows.
2. Service-level synthetic acceptance for manual expansion, encrypted report persistence and expiry, daily catch-up, and AI-assisted drafts has passed. Never use live Gmail or an external provider for development verification without separate authorization.

## Limits

This contract does not authorize any live provider or Gmail request. Development uses synthetic messages and a fake local provider server. The user has deferred the remaining Phase 2 checks that require their Gmail clicks; do not prompt for them unless they reopen those checks.

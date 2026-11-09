# Implementation roadmap

Each phase should leave a runnable and reviewable result. Do not start later-phase account access until the earlier user experience and local setup are clear.

## Phase 0 — Documentation and decisions

**Goal:** define the product, architecture, data handling, and staged scope.

**Deliverables:**

- Product plan, technical plan, roadmap, and decision register.
- Agentic development workflow, including durable progress notes and scratchpad conventions for implementation work.
- Agreement on local prerequisites and OAuth mode.
- Decision on how email content may be processed, retained, and sent to a model.

**Exit criteria:** the team can create the skeleton without inventing core product rules.

## Phase 1 — Local skeleton and proof of concept

**Goal:** create a usable local UI with Dashboard and Conversations pages before connecting real mailboxes.

**Technology:** React + TypeScript, Vite, Tailwind CSS, and shadcn/ui. This phase is frontend-only; Spring Boot, PostgreSQL, and Liquibase start in Phase 2. Use fictional sample records, clearly marked as sample data.

**Deliverables:**

- Login screen with demo sign-in only; it opens the app with fictional sample data. Do not show a Google sign-in option until OAuth is implemented in Phase 2.
- Display East as the application name on the login screen and main app shell.
- Main application shell, navigation, and responsive layout.
- Light and dark themes using the Nord palette, following the operating system preference by default; use Nord's Polar Night and Snow Storm colors for base surfaces, Frost for accents, and Aurora for semantic states.
- Dashboard with summary, urgent-action, response, and transaction card examples.
- Conversations page with mailbox picker, account color/name indicators, Gmail category tabs, thread list, sample thread reader, and visible search/filter/label controls.
- Default to the combined Inbox across accounts; use a side-by-side reading pane on larger screens and full-screen thread detail on small screens.
- Comfortable message rows by default with a toggle to compact density.
- Static all-accounts and per-account views using multiple fictional fixture mailboxes.
- Demo sign-in, page navigation, sample thread selection, and row-density toggle are interactive; search, filters, labels, and mailbox actions are visual placeholders in this phase.
- No real OAuth flow, mailbox data, AI processing, or sending in this phase.

**Exit criteria:** a user can run the visual prototype locally, use demo sign-in, navigate both pages, and understand how the planned account filtering and assistant summaries will work.

## Phase 2 — Google sign-in, Gmail-connected Conversations, and local vault

**Goal:** let any user sign in and connect their own Gmail accounts.

**Deliverables:**

- Spring Boot API foundation, PostgreSQL via Docker Compose, and Liquibase changelog setup.
- Google OpenID Connect app sign-in, replacing the demo-only authentication path for connected-account use.
- Generate a random per-install encryption key and persist it in an owner-only local key file. Load it at startup; no routine passphrase prompt or idle lock. Keep East bound to localhost and document that the key is available to the same operating-system account.
- Preserve existing local data through a one-time passphrase migration when an installation created with the previous key derivation scheme has no local key file. Offer a destructive local reset if that passphrase or key file is unavailable; do not delete or alter messages in Gmail.
- A separate connect-mailbox flow that can link additional Google identities to the signed-in user.
- Server-side OAuth code exchange, validated `state`, offline token handling when needed, encrypted refresh-token storage, and handling of Google-revoked/expired grants with a reconnect flow. Disconnect removes the local connection and purges local credentials/data; it does not call Google's revocation endpoint, which can revoke every OAuth scope granted to the project. The disconnect confirmation must explain that Google authorization may remain until the user revokes East in Google Account settings.
- User-owned mailbox records and mailbox data isolation.
- Initial per-mailbox sync of the latest 30 messages with clear progress and error states.
- Sync every 15 minutes while the app is running, with manual refresh and sync on startup.
- Conversations list and thread view backed by Gmail data using the narrowest viable read scope.
- Gmail category tabs in the combined and per-account Inbox for categories available on those Gmail accounts.
- User-initiated full-mailbox Gmail search, including older matches beyond the local initial sync; fetch full details when a user opens a result.
- Attachment names and available metadata in Conversations, with user-triggered preview for supported PDFs/images and download for all attachment types; encrypt fetched bytes and expire them with the configurable message-body cache. Do not fetch attachment bytes during background sync or send them to an AI provider in this release.
- OAuth setup guide for a developer's localhost app, including how to obtain client credentials, store them in a protected untracked local config file, and register the authorized redirect URI.
- Conversations backed by Gmail: combined Inbox by default, all-account and per-account views, supported Gmail folders and category tabs, stable account identity colors, paginated thread list, and thread reader.
- Folder/category/filter browsing pages through Gmail metadata on demand across the selected mailbox scope, so the initial 30-message sync cache does not cap the ordinary list. Message bodies remain on demand when a user opens a thread; display Gmail's total as an estimate.
- On-demand full-mailbox Gmail search, including older messages beyond the initial sync, plus account, unread/read, starred, and Gmail-label filters.
- Gmail user-label create, rename, delete, apply, and remove actions; archive, Move to Inbox, trash, Restore from Trash, spam/not-spam, read/unread, star, and supported bulk actions. Every operation must come from a user click and be ownership/scope checked server-side.
- User-click-triggered PDF/image previews and downloads; encrypt fetched attachment bytes and expire them with the configurable body cache. Never fetch bytes during background sync or send them for AI analysis.
- Compose new emails, replies, reply-all, and forwards with To/CC/BCC and basic rich text. Include forwarded attachments by default with removal controls, and let users select local attachments of any file type within the configured size/count limits. Use a safe declared MIME type or `application/octet-stream`; do not execute or preview outgoing files in East, and leave Gmail's own restrictions intact. Keep unsaved drafts and staged files encrypted locally until a user saves to Gmail Drafts, successfully sends after explicit message-specific review, or deletes the draft.
- Configurable body/attachment cache lifetime with a seven-day default; expire snippets and dependent derived data while retaining minimal inbox/navigation metadata. Retain unsaved drafts until saved or deleted.
- Keep the Phase 1 fictional demo available without configured Gmail accounts. Bind all app services to loopback and retain the existing Nord and responsive UI behavior.

**Exit criteria:** using documented local setup and without code changes or seeded email addresses, a user can start East with its local key file, sign in, connect two Gmail accounts, see and search real messages from both in Conversations, perform the supported user-confirmed mailbox and composition workflows, disconnect accounts, and retain no body/attachment cache past the configured lifetime. The app remains usable in fictional demo mode when Gmail is not configured.

## Phase 3 — Dashboard summaries and insights

**Goal:** turn synced data into useful, inspectable dashboard information.

**Deliverables:**

- Date-range selection and a record of each summary run's mailbox/date scope.
- Default Dashboard range of seven days; prompt on mailbox link for a user-confirmed 30-day report. Show an explicit metadata-only eligible-message count preview, disclose provider/model/destination and text processing before generation, ask before fetching beyond the 30-message initial allowance per selected mailbox, cap one manual expansion at 240 additional bodies, and require renewed confirmation if the count changes.
- Manual report generation and optional daily reports at the user's selected local time, covering the previous local calendar day from unexpired local cache; select at most 240 cached bodies globally and generate one catch-up report for the most recent day after a missed run.
- Email digest with links to its supporting threads.
- Concise narrative summary paragraph with separate action, reply, and transaction sections.
- Candidate action-needed and awaiting-reply detection, with reasons and review links.
- On explicit user request, generate editable new-email and reply drafts in the app using the configured model provider. Do not choose attachments; keep saving to Gmail Drafts and sending as separate user actions.
- Reply-to-prior-inquiry detection based on thread context and source links.
- Transaction candidate extraction with explicit unknown values and source evidence.
- Combined dashboard by default, with account filters and a toggle to group results by account.
- User-configured OpenAI or custom OpenAI-compatible endpoint, validated structured outputs, and clear provider disclosure before processing email.
- Catch up on one report for the most recent day after a configured report was missed while the app was closed.
- Dashboard Configure AI empty state when no provider is set; keep sync and Conversations available without a model key.
- Expire summaries and extracted insights when their source message bodies expire.
- Preserve source-mailbox provenance for combined reports and purge affected reports/insights when a source mailbox is disconnected.

**Exit criteria:** every generated claim is traceable to source mail, failures are visible, and the dashboard does not present model suggestions as guaranteed facts.

## Phase 4 — Hardening and extended features

Prioritize based on user feedback: reliable background sync, advanced search, accessibility, keyboard navigation, audit-friendly draft history, and additional Gmail-style actions. Revisit public distribution and OAuth verification only if the product will be shared beyond its local development/testing context.

## Suggested workstream boundaries for later agents

These are future implementation workstreams, not parallel changes to start before Phase 0 decisions are settled:

- **Local app foundation:** Spring Boot/React structure, Liquibase, setup docs, app shell, and sample fixture model.
- **Google identity and mailbox linking:** OAuth flow, user/mailbox schema, encrypted tokens, and ownership rules.
- **Gmail sync and conversations:** bounded first sync, incremental sync, API contract, and mailbox UI.
- **Dashboard intelligence:** data selection, summarization adapter, structured insights, evidence links, and transaction candidates.
- **Reply and organization workflows:** label changes, drafts, explicit send confirmation, and safeguards.

Agents should work against documented API contracts and separate ownership boundaries. Database changes must use Liquibase; avoid parallel edits to the same migration or shared UI shell without coordination.

Before implementation begins, establish the repository-level agent instructions and shared progress log described in [the agentic development workflow](agentic-development-workflow.md). Each active implementation agent/workstream should keep its own concise, recoverable scratchpad and leave a clear handoff.

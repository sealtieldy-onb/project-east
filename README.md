# East

East is a local email assistant for reviewing messages across multiple Gmail accounts.

It is designed for any user to connect their own accounts; account addresses and colors will come from connected account data rather than being hardcoded in the application.

## Phase 1 prototype

The fictional-data prototype remains available as a fallback when Gmail is not connected. It includes the Nord-styled Dashboard and Conversations views and does not call an AI provider.

## Run Phase 2 locally

See the [local run guide](docs/run-local.md) for the PostgreSQL, Spring Boot, and React startup steps. East remains bound to loopback and uses PostgreSQL port `15432` by default.

The protected local configuration file is [`.env`](.env). Follow the [Google OAuth setup guide](docs/google-oauth-setup.md) to configure it and register the redirect URI that matches East's backend port. Keep the client secret in `.env`; OpenAI keys belong in East settings and are encrypted at rest with the local encryption key.

### Current local verification

The Maven compile, focused backend security tests, and frontend production build pass. The twelve backend tests cover AES-GCM/AAD behavior, local key reload/permissions, OAuth and AI-key encryption, local draft/attachment encryption, explicit send confirmation, saved-draft send request binding, inline attachment byte retention/wiping, and retention-cleanup scheduling/SQL intent. Liquibase changesets 001–006 are applied to the configured PostgreSQL service; a clean temporary database applied all six changesets successfully and was dropped. The Inbox/category list fetches thread metadata from Gmail on demand instead of stopping at the initial 30-message cache. Live read-only checks loaded up to 90 of about 402 filtered Inbox conversations and 60 of about 402 All categories conversations; both connected accounts appear in the combined list. The list fetches another 30 metadata rows as the user scrolls near the end, with a manual Load more fallback and recovery for expired cursors. Message bodies remain on demand. Dashboard shows two connected mailboxes, both synced. The user completed a Star/Unstar round trip in Conversations and restored the original star state. Attachment checks were deferred by the user; draft-save, send, and other mailbox-action workflows remain unverified for Phase 2 acceptance. Saved Gmail Drafts now send the exact fingerprinted snapshot through a replacement message in the same `drafts.send` request. No Gmail mutation was made during the mailbox coverage fix. New installs load a random key from `~/.east/vault.key`, with no routine passphrase prompt or idle lock. AI summaries remain Phase 3. Retention tests inspect SQL and scheduling but do not execute a PostgreSQL purge; live Gmail workflows remain outside automated coverage.

## Project documentation

- [Product plan](docs/product-plan.md): audience, workflows, feature scope, and acceptance criteria.
- [Technical plan](docs/technical-plan.md): proposed architecture, technologies, data model, OAuth, and security requirements.
- [Implementation roadmap](docs/implementation-roadmap.md): the staged proof of concept through Gmail integration and assistant features.
- [Decisions and open questions](docs/decisions-and-open-questions.md): agreed direction, assumptions, and decisions to resolve before later phases.
- [Agentic development workflow](docs/agentic-development-workflow.md): persistent progress notes, scratchpads, handoffs, and coding-agent practices for future implementation.
- [Google OAuth setup](docs/google-oauth-setup.md): local OAuth client, consent-screen, callback, and credential setup.

## Proposed stack

- Backend: Java with Spring Boot
- Frontend: React, TypeScript, and shadcn/ui
- Database: PostgreSQL with Liquibase migrations
- Email integration: Gmail API through server-side Google OAuth 2.0

The application is intended to run locally. No hosted deployment is part of the initial plan. See the implementation roadmap for the localhost proof of concept.

## Current status

Phase 2 implementation includes local encryption, OAuth/Gmail integration, data-backed Conversations, mailbox actions, attachments, and reviewed composition. Google sign-in, two connected accounts, Gmail-backed folder pagination, and full-mailbox search are verified. User participation is still needed for attachment, draft-save, send, and remaining Gmail mutation workflows. AI summaries and agentic insights are deferred to Phase 3.

The Phase 1 demo sign-in and fictional sample data remain available. See [project progress](docs/project-progress.md) for verification details and the [Phase 1 scratchpad](docs/agent-work/phase-1-frontend.md) for its work log.

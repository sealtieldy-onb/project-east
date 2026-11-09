# Gmail connection workstream

## Objective and scope

Implement Phase 2 Google identity and Gmail account linking, Gmail API integration, sync, and Conversations data paths after the backend foundation lands. Work from the active goal and `docs/technical-plan.md`; Phase 2 includes the selected `gmail.modify` scope and user-confirmed mailbox/write workflows. AI summaries/insights remain Phase 3.

## Status

Historical workstream plan from 2026-11-09; the parent integrated OAuth/session, encrypted mailbox links, Gmail sync, search, and Conversations afterward. Current live verification is recorded in `docs/project-progress.md`: one connected mailbox has a current read-only sync, and full-mailbox search/pagination work. Phase 2 acceptance remains in progress pending a second account and user participation in mailbox-change, attachment, draft-save, and send flows.

Checkpoint: 2026-11-09; parent integration verified later on 2026-11-09.

## Plan and progress

1. Confirm exact OAuth local callback, app identity/linking split, required scopes, and Google test-mode token behavior. **Done.**
2. Document OAuth setup and protect the local config path. **Done:** `docs/google-oauth-setup.md`, root `.env.example`, root `.env`.
3. Integrate with the backend vault/session foundation without changing its files or Liquibase migration ownership.
4. Implement app sign-in and separate session-bound Gmail mailbox-linking flow, OIDC nonce/state checks, token refresh/revocation, and encrypted token persistence.
5. Implement initial and 15-minute incremental Gmail sync, 30-message baseline, category/label/thread normalization, on-demand full-mailbox search, attachment-on-click retrieval, and mailbox operations used by Conversations.
6. Wire explicit user-triggered compose/draft/review/send actions, with server-side ownership/scope checks and per-item bulk-operation results.
7. Update docs and verify with local compile/runtime checks and mock/fake Gmail tests; never use a real mailbox without explicit user instruction.

## Files and interfaces

- Requirements: `docs/technical-plan.md`, `docs/product-plan.md`, `docs/implementation-roadmap.md`.
- OAuth setup and exact callback: `docs/google-oauth-setup.md`; the current local configuration uses `http://localhost:8081/login/oauth2/code/google`.
- Local config: `.env.example` is shareable; `.env` is ignored and owner-readable/writable. Never print or copy values from `.env` into logs, scratchpads, docs, or tool summaries.
- Historical ownership rule for the original parallel workstreams: backend and Liquibase files had separate ownership until integration. That workstream has ended; current changes should follow `AGENTS.md` and coordinate migration ownership.
- Phase 2 requires `openid email profile` for identity and `gmail.modify` for Gmail reading/search, mailbox changes, drafts, and explicit user-confirmed send.

## Verification

- Official current references checked: Google's web-server OAuth, OpenID Connect, Gmail scopes, OAuth policies, and Spring Boot docs. See linked sources in `docs/google-oauth-setup.md` and `docs/technical-plan.md`.
- Original workstream checkpoint only: no Google authorization or Gmail API request had been made at that point. The later Phase 2 integration verified sign-in, one mailbox connection, read-only sync, and full-mailbox search/pagination after the local reset; remaining user-click workflows are still pending.
- Parent integration compiled the backend/frontend and applied migrations 001–005; current live and unverified workflows are listed in the status above.

## Decisions and blockers

- The local web OAuth callback is fixed to `localhost`, not `127.0.0.1`, so cookies and redirect matching remain on one host.
- User confirmed the OpenAI key must be entered via East settings and encrypted in the vault; never put it in `.env`.
- Google OAuth `Testing` refresh tokens with Gmail scopes expire after seven days; document this in local setup and show reconnect state in the application.
- Historical checkpoint only: no live Google authorization or Gmail API request had been made by this workstream agent. The parent later verified Google sign-in and read-only Gmail operations.

## Handoff

The original handoff to the backend foundation agent is complete. The parent integrated the OAuth/Gmail workstream and verified the current local sign-in, connected-mailbox sync, and search path. Keep the remaining acceptance items and current implementation status in `docs/project-progress.md`; do not treat this historical workstream plan as an active blocker.

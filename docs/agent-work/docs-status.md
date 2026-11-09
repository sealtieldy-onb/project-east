# Documentation status audit

## Parent follow-up — 2026-11-09

The stale progress claims noted in the original audit were refreshed in `README.md`, `docs/run-local.md`, `docs/project-progress.md`, and `docs/agent-work/phase-2-integration.md`. Later verification confirms backend Maven compilation passed for 113 Java sources, the frontend production build passed with 2,078 modules, Liquibase changesets 001–005 are applied to the configured database and all five passed on a clean temporary database, and East is running locally on ports 8081/5173. The user subsequently completed Google sign-in after the local reset, connected one mailbox, verified current read-only sync, and verified full-mailbox search pagination (30 loaded of approximately 201, then 60). No Gmail mutations, attachment opens/downloads, draft saves, or sends were performed; Phase 2 acceptance remains in progress pending a second mailbox and user participation in user-click workflows. The local-key-file decision means no routine passphrase prompt or idle lock.

## Objective

Audit the Phase 2 README, central progress log, integration scratchpad, and workstream notes against the current parent-reported build/runtime state. Do not read `.env`, expose credential values, or edit shared documentation.

## Findings

These findings and observed-state notes below record the documentation state during the original audit, before the parent follow-up above. They are historical and superseded where the follow-up reports later verification.

- `README.md:25` is stale: it says backend compilation and database migration verification have not completed and tells the user to stay on the fictional demo path. Parent reports backend compile passed, both Liquibase changesets applied, and the connected local app is running. OAuth remains unavailable on the current runtime because East is on port 8081 while the configured callback remains 8080; the unrelated Capstone process occupies 8080. Vault is not initialized by the parent and no Google authorization has been started.
- `README.md:27-31` presents `localhost:8080` as the exact current callback. Clarify that the callback must match East's actual backend port and the Google Console allowlist; do not suggest starting OAuth with the current 8081/8080 mismatch.
- `README.md:9` calls the frontend-only prototype the “current implementation.” The Phase 1 description is still correct, but clarify that Phase 2 now adds the local backend while keeping the demo path.
- `README.md:21` still correctly documents the Vite URL, but the statement that it matches the OAuth callback/session setup is incomplete for the current backend running on 8081.
- `README.md` describes only the Phase 1 `cd frontend && npm run dev` run path. There is no `docs/run-local.md`; add or update local run instructions to describe starting Compose PostgreSQL, backend, and frontend, while noting OAuth is presently disabled until its callback and Google Console URI match.
- `docs/project-progress.md:36,39,43,45,47,49-51,66-67,71` contains outdated pending claims: no migration, backend compile pending, Maven resolution pending, draft/attachment implementation incomplete, and no runtime verification. Replace these with observed checks: `mvn ... compile` passed; PostgreSQL is on loopback port 15432; Liquibase changesets 001 and 002 applied; frontend production build passed (2,077 modules); local frontend/backend started on 5173/8081. Keep the absence of tests and Gmail/OAuth calls explicit.
- `docs/project-progress.md:32` says the OAuth callback is fixed at 8080 without mentioning the current backend is running on 8081. Update the current-configuration note with the mismatch and the fact that OAuth is therefore disabled.
- `docs/agent-work/phase-2-integration.md:9,28,32,35,40-44` is stale on work in progress, build count, deferred Maven compile/migration, and unfinished endpoint handoffs. Replace the status/next steps with current integration and verification results, retaining the explicit no-OAuth/no-Gmail constraint.
- Several workstream scratchpads still say the parent compile/migration is pending (for example `google-oauth.md:9`, `gmail-connection.md:9,46`, `settings-api.md:20`, `retention-cleanup.md:9,35`, `gmail-rest-client.md:9,27,38`, `mail-actions.md:9`, `attachment-ui.md:9`, and `schema-review.md:16`). These may be left as historical handoff records; if their status is meant to be current, add a dated parent-verification note rather than rewriting what each agent actually did. Runtime endpoint behavior remains only partially verified.
- `docs/run-local.md` is absent. `README.md` is currently the only local run guide.

## Original observed state from the first audit (historical; superseded by Parent follow-up above)

- Database accepts connections on loopback port 15432; both Liquibase migrations have run.
- Backend Maven compile passed; frontend production build passed.
- Local backend and frontend sessions are running on ports 8081 and 5173.
- OAuth has not been started. The configured callback remains on 8080 and that port is occupied by an unrelated local app, so East's current 8081 instance must keep OAuth disabled.
- The vault remains uninitialized by the parent. No real Gmail workflow has been exercised.
- `.env` was not read or printed during this audit.

## Checks

- Read README, central progress, integration scratchpad, and workstream status lines with line-numbered source output.
- Confirmed no `docs/run-local.md` exists.
- No code or shared documentation edited.

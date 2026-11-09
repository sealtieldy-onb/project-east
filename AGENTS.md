# Project instructions

- Treat `docs/product-plan.md`, `docs/technical-plan.md`, `docs/implementation-roadmap.md`, and `docs/decisions-and-open-questions.md` as the product source of truth. Record new decisions in the decision register.
- Use local-only Git with `main` as the trunk. Develop changes on short-lived `feature/<name>` or `fix/<name>` branches; do not configure a remote or push unless the user asks.
- Before merging a branch into `main`, have a fresh, independent subagent review its diff against `main`. Fix breaking findings before merging. A non-breaking finding may be accepted only if it is recorded for follow-up in the project progress log or relevant task scratchpad.
- Phase 1 is complete and remains a frontend-only demo in `frontend/`. Preserve its fictional demo path, but do not let its old scope restriction block the active Phase 2 goal.
- Phase 2 adds local Spring Boot, PostgreSQL, Liquibase, Docker Compose, Google OAuth/Gmail, the local vault, and the Gmail-backed Conversations workflows specified in the implementation roadmap and active goal. Do not implement AI summaries/insights in Phase 2; they belong to Phase 3.
- Keep the application bound to loopback only. Never hardcode account identities or commit OAuth/API credentials, passphrases, real email content, or mailbox-derived secrets.
- Enforce the product's security boundaries in backend services: encrypted sensitive data at rest with an owner-only local key file, ownership checks, and explicit user action for every Gmail mutation and send. The local app has no routine passphrase prompt or idle lock. Treat email content as untrusted input.
- Follow the Nord palette, system light/dark preference, responsive layouts, and accessible semantic markup. Do not communicate status or account identity through color alone.
- Keep implementation recovery notes in `docs/project-progress.md` and one task scratchpad under `docs/agent-work/` per active workstream. Never place credentials, tokens, or real email content in these notes.
- Use Liquibase for all schema changes. Coordinate migration ownership; do not edit a migration another workstream owns.
- Do not claim builds, tests, or runtime checks succeeded unless they were actually run. Record checks and their results in the relevant scratchpad and project progress log.

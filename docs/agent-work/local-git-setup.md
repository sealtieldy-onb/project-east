# Local Git setup

## Objective and scope

Keep Project East versioned locally with `main` as the trunk and short-lived feature/fix branches. Do not configure a remote or push code until the user asks.

## Status

In progress. The repository bootstrap and Phase 2 feature branch are being created on 2026-11-09.

## Plan and progress

- Confirm no Git repository exists and review ignore patterns before staging.
- Ignore local environment files, OAuth/key material, database state, dependency folders, and build output.
- Create the initial local snapshot on `main`, then create `feature/phase-2-acceptance` for ongoing Phase 2 changes.
- Record branch reviews before merges: a fresh independent subagent reviews the diff against `main`; blocking findings are fixed, while non-breaking findings may merge when documented for follow-up.

## Files and interfaces

- `.gitignore` excludes `.env` and `.env.*` except `.env.example`, `.east/`, database volumes/backups, dependency folders, build output, and common local credentials/key files.
- `AGENTS.md` and `docs/agentic-development-workflow.md` define branch and review rules.
- `docs/decisions-and-open-questions.md` records the agreed local-only workflow.

## Verification

- Not yet complete; record repository status, branch, commit, ignored-secret checks, and staged-file review here after bootstrap.
- No tests or application builds are part of Git repository setup.

## Decisions and blockers

- The workspace was not previously a Git repository. Global Git author configuration is available; author values were not read or changed.
- No remote should be added.

## Handoff

- Finish staging review, make the local bootstrap commit, create `feature/phase-2-acceptance`, and ensure the active Phase 2 task continues on that branch.

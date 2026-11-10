# Report generation invalid-result feedback

## Task

Resolve Browser Comment 3: generating a Dashboard report showed “Report generation returned an invalid result” even though the provider connection test passed.

## Diagnosis

The backend's `EXPANSION_REQUIRED` response reports the number of additional eligible messages inside `coverage.additionalMessageCount`. The frontend parser looked only for a top-level `additionalMessageCount`, so it rejected this valid response. For a report with more than the initial per-mailbox body allowance, this stopped the consent flow before the UI could ask whether to retrieve more message bodies. The connection test can pass independently because it checks provider connectivity and a small test-only structured response; this issue was in the frontend report-response contract, not API-key validation.

## Change

The frontend now reads the additional-message count from the top-level field when present, or from the required coverage object otherwise. It validates the coverage object before accepting the expansion response. This lets the existing UI continue to the explicit expansion choice without changing the report's body-read or provider-disclosure safeguards.

## Safety and verification

- No configured provider, API key, provider request, report generation, Gmail request, or mailbox content was accessed.
- No tests were added or run.
- `git diff --check` passed.
- The frontend build was not run because this isolated worktree has no `frontend/node_modules` installation.

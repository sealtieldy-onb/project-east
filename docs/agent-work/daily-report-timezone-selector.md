# Daily report time zone selector

## Request

Replace the manually typed daily-report time zone field in Settings with a dropdown so users can choose a supported time zone.

## Product constraints

- Daily reports use the schedule's configured time zone and cover the previous local calendar day.
- Keep the saved IANA time-zone identifier unchanged in the API contract.
- Preserve the schedule's existing disclosure, enablement, and validation flow.

## Implementation

- Replaced the free-form text input with a native, keyboard-accessible select.
- Populate options with `Intl.supportedValuesOf("timeZone")`, with UTC first; include a small common-zone fallback for older browsers and preserve any currently saved valid IANA alias.
- Keep option values as exact IANA identifiers while making underscores easier to read in the option label.
- Added a short hint describing the selected zone's effect on the schedule.

## Checks

- `git diff --check` passed.
- Frontend build not run because this worktree does not have `frontend/node_modules` installed.
- No tests were added or run, per task instructions.

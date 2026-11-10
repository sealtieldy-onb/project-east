# AI Reports custom dropdowns

Date: 2026-11-10
Status: implementation complete; independent review pending.

## Scope

Replaced the AI Reports Dashboard's native Report period and Account scope selects with custom dropdowns using the app's established menu pattern.

## Changes

- Preserved the existing period values, mailbox scope values, and state-change handlers.
- Added menu semantics, selected-state announcements, keyboard navigation, Escape-to-close, outside-click/focus dismissal, and focus restoration.
- Styled the triggers and menus with the existing theme variables and added a narrow-screen rule for the controls.

## Checks

- `git diff --check`: passed.
- No build, tests, browser/runtime check, Gmail request, or AI-provider request was run.

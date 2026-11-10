# AI Reports custom dropdowns

Date: 2026-11-10
Status: implementation complete; independent review pending.

## Scope

Replaced the AI Reports Dashboard's native Report period and Account scope selects with custom dropdowns using the app's established menu pattern.

## Changes

- Preserved the existing period values, mailbox scope values, and state-change handlers.
- Added menu semantics, selected-state announcements, keyboard navigation, Escape-to-close, outside-click/focus dismissal, and focus restoration.
- Capped the menu width at its filter container so right alignment cannot push it outside the viewport on narrow screens.
- Derived an active mailbox scope from current mailbox data for the filter, report query, and generation requests. When a selected mailbox disappears, the UI and request scope fall back together to all connected accounts, and the stale state/disclosure are cleared.
- Styled the triggers and menus with the existing theme variables and added a narrow-screen rule for the controls.

## Checks

- `git diff --check`: passed after the reviewer fixes.
- No build, tests, browser/runtime check, Gmail request, or AI-provider request was run.

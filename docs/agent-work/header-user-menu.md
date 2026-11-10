# Header user menu

## Scope

Implement the top-bar account button and dropdown from browser annotation 2. The menu reuses the existing Settings and logout callbacks.

## Implementation

- Replaced the static avatar and adjacent sign-out icon with a keyboard-focusable avatar button and disclosure panel.
- Added Settings and, for connected sessions, Log out actions using the existing handlers. Demo sessions retain their existing Settings flow.
- Added outside-click dismissal, Escape dismissal with focus returned to the trigger, focus-out dismissal, and theme-token styling.

## Checks

- `git diff --check` passed.
- No build or tests were run.

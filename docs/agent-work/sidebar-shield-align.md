# Sidebar shield alignment

## Scope

Fix the shield icon alignment in the sidebar note “Mailbox access stays in this local session.” The icon should sit vertically centered beside both single-line and wrapped text.

## Changes

- Changed `.sidebar-note` from `align-items: flex-start` to `align-items: center` in `frontend/src/index.css`.

## Checks

- `git diff --check`: passed.
- No build, tests, or browser/runtime check was run.

# Conversations folder rail layout — 2026-11-10

## Scope

Address the mailbox scope picker’s right-edge spacing, make the folder and label navigation usable in a constrained-height frame, and identify the owning mailbox for each Gmail label with both a color marker and text.

## Implementation

- Removed the rail picker’s extra horizontal margin so its trigger stays within the rail padding.
- Kept the mailbox scope picker and action note in place while folders, label management, and labels share a single scrollable rail region.
- Added a separate account-color marker beside each label. The mailbox display name stays visible and the button’s accessible name includes the owner; the Gmail label color remains separate.
- At short viewport heights, the Conversations shell can shrink to the viewport and its content can scroll. The mail frame keeps a usable minimum height so its lower edge is reachable.

## Checks

- `git diff --check` passed.
- No build, tests, or browser/runtime check was run.

## Review follow-up

- The independent review found that the short-viewport `overflow-y: auto` rule had equal specificity to the base Conversations `overflow: hidden` rule in `index.css`, so the base rule won by source order. Increased selector specificity by scoping the override through the `.app-main-column` parent; this keeps the lower content frame vertically scrollable at short heights.
- No tests, build, or browser/runtime check was run for this follow-up.

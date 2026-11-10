# Message expand modal

## Goal

Add an accessible way to open one Gmail message in a larger reading surface so long email bodies are easier to inspect.

## Implementation

- Added an Expand button to each connected message card.
- The native modal shows the subject, sender, recipients, timestamp, message body, and attachment actions.
- Uses the existing safe body renderer. External body images remain blocked until the user clicks the per-message load control.
- Uses `<dialog>.showModal()` for modal focus management and Escape handling, with a visible close button and backdrop dismissal.
- Default sizing is 75vw by 90vh; tune with `--conversations-message-expanded-width` and `--conversations-message-expanded-height`. On narrow screens, the modal expands to at least 92vw while respecting viewport margins.

## Changed files

- `frontend/src/components/Conversations.tsx`
- `frontend/src/components/conversations.css`

## Checks

- `git diff --check` passed.
- No build, test suite, browser/runtime check, Gmail call, or provider call was run.

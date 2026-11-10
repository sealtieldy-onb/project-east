# Settings button readability

## Scope

Addressed browser comments 2, 3, and 4 for the Settings dialog buttons “Remove configuration,” “Erase local data,” and “Done.”

## Changes

- Added stable classes and explicit theme-token text/surface colors for the three controls.
- Increased contrast and weight for the destructive-data entry point while retaining its danger styling.
- Kept hover, focus-visible, and disabled behavior available; disabled destructive/provider controls retain a visible disabled treatment with more legible text.

## Checks

- `git diff --check` passed.
- No build, tests, or browser check were run.

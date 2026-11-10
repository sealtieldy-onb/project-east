# AI Reports header and content layout

## Request

Rearrange the Dashboard's AI Reports card so its top bar contains the icon, AI Reports label, “Your mail, distilled” title, and AI settings button. Put the report description and controls/content below the header at the card's full width.

## Changes

- Moved the sparkle icon beside the report title in a dedicated header row.
- Placed the AI settings button in that same row.
- Moved the report description into the full-width content area below the header.
- Changed the card from an icon-and-content side-by-side layout to a vertically stacked header and content layout; allow the header to wrap on narrow screens.

## Checks

- `git diff --check` passed.
- No build, automated tests, or browser/runtime checks were run for this change.

## Scope

Only the Dashboard AI Reports card markup and its stylesheet were changed, plus this workstream note and the project progress log. Report generation and disclosure behavior are unchanged.

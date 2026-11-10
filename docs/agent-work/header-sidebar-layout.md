# Header and hideable sidebar layout

## Request

Move the East mark and name into the app header, place the navigation sidebar below that header, and make the sidebar hideable. Keep the page title, user menu, account controls, and mobile navigation available.

## Changes

- Changed the app shell to a two-row grid: a full-width header above the navigation and main content.
- Moved the East brand into the header alongside the current page title.
- Added an accessible sidebar toggle with a changing label and expanded state; hiding the sidebar lets the main content use the full width.
- Kept the compact bottom navigation on small screens, adjusted the main content height around the header and nav, and retained the page title for assistive technology at very narrow widths.

## Checks

- `git diff --check` passed.
- No build, automated tests, or browser/runtime checks were run for this change.

## Scope

Only the app shell/header/sidebar markup and global layout styles were changed, plus this workstream note and the project progress log. Account-menu and page behavior are unchanged.

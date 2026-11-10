# Settings modal layout

## Objective

Address browser Comment 1 by centering the authenticated Settings modal and keeping its LOCAL WORKSPACE header visible at the top while the settings content scrolls.

## Changes

- Position and size the authenticated native dialog to stay centered within the viewport on desktop and mobile.
- Keep the dialog content scrollable while making its header sticky, so the title and close control remain available throughout the longer settings form.
- Leave the fictional demo settings dialog on its existing layout.

## Verification

- `git diff --check` passed.
- No tests or build were run.

## Review status

Implementation is ready on `fix/settings-dialog-layout`; awaiting independent review against `main`.

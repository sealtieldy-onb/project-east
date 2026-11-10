# Modal backdrop and placement consistency

## Request

Browser Comment 6 asked to apply the centered, blurred-background treatment from Settings to all applicable East modals.

## Inventory and changes

- Confirmed the modal surfaces use native `<dialog>` with `showModal()`: connected Settings, fictional demo Settings, the message composer, attachment preview, and expanded full-email view.
- Added a shared fixed, viewport-centered dialog placement and a shared dimmed, blurred `::backdrop` rule.
- Removed per-dialog backdrop overrides so these surfaces receive one consistent treatment.
- Existing dialog-specific dimensions and scrolling remain in place, including near-full-screen layouts on small screens and scrollable long content.
- Non-modal menus and the Conversations reading pane are unchanged.

## Checks

- `git diff --check` passed.
- No tests or production build were added or run.
- No backend, database, Gmail, provider, or message-content behavior was changed or accessed.

## Handoff

This is a presentation-only frontend change. A fresh independent review passed, and it was squash-merged into local main on 2026-11-10.

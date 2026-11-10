# Browser annotation fixes

Date: 2026-11-10
Branch: `feature/browser-feedback-polish`
Status: implementation complete; independent review pending

## Scope

Resolve the 13 new browser comments covering cross-browser dropdown sizing, modal header/scrollbar layout, Dashboard insight ordering, page transition stacking, Conversations frame focus, message-link contrast, unread-row distinction, narrow filter scrolling, reader-action overflow, email image formatting, and remembered label reordering.

## Implementation notes

- Keep the app loopback-only and preserve explicit user actions for every Gmail mutation and send.
- Treat email HTML as untrusted. External message images stay blocked until the existing per-message user action.
- Persist label display order as a local UI preference scoped to the connected mailbox; do not change Gmail label state.
- Preserve unrelated existing workspace changes.

## Changes

- Shared dropdown menus now size against the visible viewport, wrap long choices, and recalculate after viewport, scroll, and trigger-size changes. Browsers without the Popover API use a positioned portal that stays within a native modal when one contains the control.
- Settings, demo Settings, and Help now keep their topbar outside the independently scrollable content region. Composer, attachment preview, and expanded-message dialogs already had a fixed header and dedicated scroll area.
- Dashboard actionable insights sort by urgency, then deadline, cited-message recency, and stable provider order; informational insights follow actionable items. Dashboard/Conversations page motion now runs inside the content column beneath the sidebar.
- Conversations message cards use keyboard-only focus outlines, unread rows have a stronger theme-aware tint, email links use readable light/dark colors, and narrow filter controls support horizontal wheel/keyboard scrolling while retaining touch scrolling.
- Reader actions use a two-row grid with horizontal overflow. User labels can be dragged within their mailbox, reordered with accessible up/down controls, and remembered in browser storage using mailbox and label IDs. Disconnected mailbox entries are pruned.
- Email rendering now preserves a narrow allowlist of static size, spacing, and alignment styles, validates presentation dimensions, and keeps oversized images within the reader. Styles that could load resources or position content are removed; external images remain inert until the existing explicit per-message action.

## Verification

- Implementation agents ran `git diff --check` on their scoped files.
- Fresh independent review against `main` found no remaining blocking source findings. The reviewer reran the frontend build after a nullable-ref fix in `CustomDropdown.tsx` and reran the focused sanitizer/inline-image tests.
- `npm run build` passed: TypeScript completed and Vite transformed 2,085 modules. Vite emitted a non-blocking 511.93 kB JavaScript chunk-size advisory.
- `mvn -q -f backend/pom.xml -Dtest=MailHtmlSanitizerTest,MailReadServiceInlineImageTest test` passed.
- `git diff --check main` passed.
- No browser interaction, Gmail request, or AI-provider request was made. Cross-browser popup and responsive interaction behavior remains source-reviewed but was not exercised in a live browser.

## Non-blocking follow-up

- The production bundle is 511.93 kB, above Vite's 500 kB advisory threshold. This is a size advisory, not a build failure; consider splitting large routes/components in a separate performance task.

## Verification

Pending implementation and independent review.

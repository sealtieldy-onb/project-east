# Button readability audit

## Request

Address the low-contrast Add account button and check other button treatments across East.

## Audit scope

Reviewed the shared Button variants and button states, plus page-specific controls in the app shell, login, demo and connected Dashboards, Settings, Vault notices, Conversations, and the composer. Checked both light and dark theme tokens, hover/focus treatments, disabled text controls, and icon-only disabled controls.

## Findings and changes

- The shared outline variant relied on the base Button foreground and used a flat surface. It now sets its foreground explicitly and uses a lightly raised surface and stronger token-mixed border. This applies to Add account and other outline buttons.
- Some small text controls used `accent-ink` on neutral surfaces. In dark mode that token is Polar Night text on a raised Polar Night surface; the full-email Expand, external-image consent, attachment controls, and body retry are now theme text.
- Some small text controls used the light Frost accent color for text, which measured 3.82:1 on the light surface and 3.44:1 on the pale accent surface. Source-message, vault-notice, list-retry, recipient-action, and AI-assist text now uses the theme text color; icons retain the accent.
- The Settings reset confirmation used danger red as text on a primary accent background, and the composer delete action used danger red text on the dark surface. Both now use readable theme text over an Aurora-tinted surface and retain a red-tinted border/background cue.
- Shared disabled Button variants and text controls in the report filters, Vault notices, Conversations, and composer had low opacity values. They now use the muted theme text color without fading the foreground; icon-only disabled controls remain de-emphasized at a higher opacity.
- Independent review found that disabled composer primary actions (including “Review before sending,” exact-message Send, and “Generate editable draft”) still looked enabled. Their disabled styles now switch to the raised neutral surface, muted readable text, and a stronger border. Shared default buttons use the same visible disabled treatment. The native disabled state and explicit review-then-send sequence remain in place.
- The same review found that the disabled demo Reply Send button still used an accent background close to the enabled state. It now uses the same raised neutral surface, muted readable text, and stronger border treatment while remaining natively disabled.
- Sidebar Settings/Help labels now use the readable muted text token. The account monogram uses the theme text color over its accent surface.

## Checks

- `git diff --check` passed.
- Static sRGB calculations with the current theme tokens and CSS mixes: outline text on its new light/dark background measured 11.32:1 / 8.32:1; danger-button text on its tinted light/dark backgrounds measured 9.79:1 / 7.84:1; and muted text on the disabled raised surface, now used for disabled primary buttons as well, measured 4.82:1 / 6.39:1. These calculations cover the CSS token mixes used by the updated controls, not every browser-composited state.
- No tests were added or run. The frontend build was not run because this worktree has no `frontend/node_modules` installation.
- No live mailbox or provider data was accessed.

## Follow-up

The independent review identified disabled primary states that the static source audit did not flag; composer, shared default, and demo Reply Send disabled styles now share a clear neutral treatment. Browser/runtime appearance remains unverified for this branch.

## Merge outcome

A fresh independent review passed after the two disabled-state fixes; the same reviewer re-reviewed the updated branch. The change was squash-merged into local main on 2026-11-10. No build or tests were run; browser/runtime appearance remains unverified.

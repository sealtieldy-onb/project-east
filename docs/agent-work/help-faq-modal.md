# Help and FAQ modal

## Workstream

Browser Comment 7: make the sidebar Help control open a useful, accessible FAQ modal.

## Changes

- Replaced the disabled sidebar Help control with a keyboard-accessible button.
- Added a native modal dialog with answers about using East, supported AI providers/model identifiers, supported Gmail workflows, and the local/provider privacy boundary.
- Clarified that the fictional demo is available from the sign-in screen and that enabling daily reports sends eligible previous-day cached email text to the configured provider on schedule.
- Kept the answer text aligned with the product, technical, roadmap, and decision documents: OpenAI or an OpenAI Chat Completions-compatible endpoint; user-selected model ID; explicit disclosure before report processing; user-initiated Gmail changes and message-specific send confirmation; no claim of full Gmail parity.
- Styled the dialog to match the Nord theme and system color mode, with centered placement, a blurred backdrop, and a narrow-screen layout.

## Checks

- `git diff --check` passed after the follow-up edits.
- No build or tests were run for the follow-up wording changes, as requested.

## Handoff

No product decision or schema change was required. A fresh independent review passed after the two content follow-ups, and the feature was squash-merged into local `main` on 2026-11-10. No build or tests were run.

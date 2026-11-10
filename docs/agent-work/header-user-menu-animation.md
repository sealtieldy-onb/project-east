# Header account menu animation

## Task

Resolve Browser Comment 2 by animating the header account actions panel when it opens and closes.

## Diagnosis

The panel was conditionally hidden with the HTML `hidden` attribute, which switches it to `display: none` immediately and prevents an exit transition.

## Change

The panel now stays mounted and transitions opacity, vertical offset, and scale in both directions. The close transition delays `visibility: hidden` until it finishes, while `aria-hidden` and `inert` update immediately so closed actions are not exposed to assistive technology or keyboard focus. Existing Escape dismissal and avatar focus restoration, outside-click handling, dynamic account label, Settings action, and Log out action remain in place. Reduced-motion preferences disable the transition.

## Verification

- `git diff --check` passed.
- No tests were added or run.
- The frontend build was not run; this isolated worktree has no `frontend/node_modules` installation.
- No credentials, provider requests, Gmail requests, or mailbox content were accessed.

## Merge outcome

A fresh independent review passed. The change was squash-merged into local main on 2026-11-10.

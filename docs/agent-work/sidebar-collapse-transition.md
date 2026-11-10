# Sidebar collapse and restore transition

## Request
Animate the app sidebar when the user hides or restores it.

## Findings and change

The `main` branch already has an accessible header toggle and `sidebarOpen` state. The closed state used `display: none`, which removed the sidebar before a transition could run. The sidebar now collapses with the desktop grid track while fading and sliding left; the mobile bottom navigation slides down while the content height follows its open/closed state. The existing reduced-motion media rule reduces transition duration. The sidebar receives `aria-hidden` and `inert` while closed so its navigation controls are not available to keyboard or assistive-technology users during the visual exit.

## Checks

- `git diff --check` passed.
- No tests were added or run.
- No build or browser runtime check was run.

## Merge outcome

A fresh independent review and a re-review against current main passed with no findings. The change was squash-merged into local main on 2026-11-10.

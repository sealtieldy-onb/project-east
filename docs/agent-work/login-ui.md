# Demo login UI scratchpad

## Objective and scope

Create the Phase 1 East demo login screen in `LoginPage.tsx` with a responsive Nord-themed layout and an explicit fictional-data explanation. Only demo sign-in is interactive; no Google auth or connected mailbox claims.

## Status

Complete. Last checkpoint: 2026-11-08.

## Plan and progress

- Read the product summary, Phase 1 roadmap, and Nord/app-name decisions.
- Added a two-column login/preview layout, mobile adaptation, demo-only sign-in callback, and fictional-data disclosure.

## Files and interfaces

- `frontend/src/components/LoginPage.tsx` exports named and default `LoginPage`, accepting `{ onDemoSignIn: () => void }`.
- `frontend/src/components/login-page.css` owns login-prefixed selectors and uses shared theme variables with Nord fallbacks.

## Verification

- No build, tests, or runtime checks run (as instructed).
- Source review only.

## Decisions and blockers

- No Google sign-in button; its planned phase is stated in text.
- The fake inbox preview is labeled as fictional demo content and does not claim to be connected.

## Handoff

Integrate `LoginPage` by importing its default or named export and passing `onDemoSignIn`. Parent app provides the shared theme variables.

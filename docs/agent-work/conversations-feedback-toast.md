# Conversations feedback toast and source highlight follow-up

Date: 2026-11-10

## Findings

- Dashboard source navigation intentionally scrolls to and programmatically focuses the referenced email message. A separate `is-report-source` class added the bright outline and tinted background. Keep the navigation and keyboard-visible focus behavior, but remove the persistent source-only styling.
- The Conversations label selector was 112px wide, and a later rule forced its trigger to 36px high while adjacent action buttons were 29px.
- Conversations mutation feedback and active-operation status shared an inline banner slot above the mail panes. Completed feedback should appear as a top-right toast and dismiss after five seconds. Keep the active-operation status visible until the operation completes.

## Changes

- Removed the source-only message-card highlight while retaining scroll-to-message and programmatic focus.
- Reduced the label trigger to 100px wide and 29px high on desktop, matching the height of adjacent action buttons; aligned tablet/mobile trigger heights to the action buttons at each breakpoint.
- Made all feedback in the Conversations banner slot fixed top-right notifications below the application header. Completed success, partial, and error feedback clears after five seconds; the busy state remains until its operation completes. Preserved the close button, live-region roles, and bulk outcome details.

## Verification

- `npm run build` — passed (2,085 modules transformed). Existing bundle-size advisory remains: 512.05 kB minified JavaScript against the 500 kB threshold.
- `git diff --check` — passed.
- Read-only Yab inspection at 1280×800 measured the label trigger at 100×29px and the action button at 100×29px in a temporary DOM layout preview. The toast preview appeared fixed at top 88px and right 16px (below the 76px header). The source card's removed highlight class no longer applies an outline; a programmatically focused pointer-origin card did not match `:focus-visible`.
- No real conversation was opened during verification, and no Gmail mutation or AI-provider request was made. The five-second dismissal is verified by the component effect and build; no real mailbox action was triggered solely to surface a notification.

## Review, merge, and restart

- Fresh independent review of `5f6d14f` against `main` passed with no blocking findings. The reviewer confirmed `git diff --check main`, `npm run build`, notification timeout cleanup, accessible focus behavior, and the live UI dimensions without opening a message or triggering a mailbox action.
- Merged to local `main` as `534a5fd` on 2026-11-10. No remote was configured and nothing was pushed.
- Stopped only the Project East service processes after confirming their working directories: Spring Boot/Maven PIDs 74025 and 74287; Vite/npm PIDs 74393 and 74616. PostgreSQL PID 837 remained running.
- Restarted from the merged checkout using the documented commands: from `backend/`, `EAST_SERVER_PORT=8080 EAST_GOOGLE_REDIRECT_URI=http://localhost:8080/login/oauth2/code/google mvn spring-boot:run`; from `frontend/`, `npm run dev`.
- Backend PID 98409 started on `127.0.0.1:8080`; Liquibase found all 11 changesets current, with 0 to execute. Vite PID 98390 started on `127.0.0.1:5173`. PostgreSQL remained on `127.0.0.1:15432`.
- Loopback checks returned HTTP 200 for `/`, HTTP 401 for the unauthenticated direct `/api/v1/dashboard`, and HTTP 401 for the same path through the Vite proxy. Both service listeners are loopback-only. The backend restart cleared its in-memory sign-in session; sign in again if East requests it.
- No Gmail mutation/send or AI-provider request was initiated. No test suite was run.

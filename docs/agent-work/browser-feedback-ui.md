# Browser UI feedback workstream

Date: 2026-11-10

Branch: `feature/browser-ui-feedback`

## Scope

Implement the user's annotated browser feedback across the East app shell, Dashboard, and Conversations:

- Animate the Gmail refresh indicator and show skeletons while the initial conversation list or selected message detail is loading.
- Make the Dashboard report content a bounded scrolling frame. Color-code insight cards by urgency, keep urgency text visible, and let the cards flow into responsive two- or three-column layouts where space allows.
- Slide Dashboard and Conversations between each other in carousel order; source-message links should enter Conversations from the right. Slide the sidebar off the left edge when hidden and animate all modal dialogs from the bottom.
- Remove the routine signed-in banner, the East tagline, and the unused global search control.
- Apply one custom checkbox treatment and a shared custom dropdown treatment throughout the app.
- Hide account-specific bulk-label controls when selected conversations span multiple accounts, and animate the bulk controls as they appear or disappear.
- Keep filter chips reachable in narrow list panes, and constrain the conversation action controls to two visible lines with scrolling when they overflow.

## Work status

Implementation is complete on the local feature branch `feature/browser-ui-feedback`.

The app shell, dashboard report, Conversations UI, shared custom dropdown, modal/sidebar/page animations, loading states, selection controls, and narrow-pane scrolling were updated. During independent review, the reader was additionally gated by selected thread and active mailbox; changing mailboxes clears a now-out-of-scope reader before the next render.

### Verification and review

- `npm run build` in `frontend/` passed: TypeScript and Vite production build completed (2,085 modules). Vite reports the generated JavaScript chunk is 505.92 kB, slightly above its 500 kB advisory threshold.
- `git diff --check` and `git diff --cached --check main` passed.
- No test suites were run.
- Fresh independent review by `/root/independent_review` passed after the thread/mailbox-scope fixes. The reviewer confirmed selected message body, labels, actions, report-source focus, and reply/forward composition use matching detail. Non-blocking follow-ups: shared dropdown has no type-ahead for long option lists; bundle size remains above the advisory threshold.
- Browser screenshot/UI interaction verification was unavailable in this run; the in-app browser surface could not be claimed through the available browser automation runtime.

### Merge and local services

The reviewed feature commit `5c19f38` was merged locally to `main` as `f19a653`. No remote was configured and no push occurred.

After confirming the old app PIDs belonged to this checkout (`27189` Vite in `frontend/`; `27093` Maven and `27227` Java in `backend/`), sent `SIGTERM` to those three PIDs. PostgreSQL was not stopped or started; it remained available in Docker at `127.0.0.1:15432` (listener PID 837).

Restarted from the merged `main` checkout with the commands documented in `docs/run-local.md`:

- From `backend/`: `EAST_SERVER_PORT=8080 EAST_GOOGLE_REDIRECT_URI=http://localhost:8080/login/oauth2/code/google mvn spring-boot:run` (Spring Boot PID 77891; Liquibase reported all 11 changesets current; listener on `127.0.0.1:8080`).
- From `frontend/`: `npm run dev` (Vite PID 78149; listener on `127.0.0.1:5173`).

The first restart attempt in the default sandbox was blocked with `EPERM` when opening local sockets; the same documented commands succeeded with local-network permission. Loopback checks returned HTTP 200 for `http://127.0.0.1:5173/`, HTTP 401 for the unauthenticated direct Dashboard API request, and HTTP 401 through the Vite API proxy. The 401s are expected without a signed-in session and confirm the backend/proxy responded. The Spring Boot restart cleared its in-memory Google sign-in session; sign in again before using connected Gmail.

`docs/project-progress.md` has the corresponding restart/review record. Existing unrelated local edits and untracked files were preserved; no test suite was run.

## Privacy and behavior constraints

Keep the app loopback-only. Preserve explicit user action for Gmail mutations and sends. Do not record credentials, tokens, real email content, or mailbox-derived secrets in this note.

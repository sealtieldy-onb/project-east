# Main app runtime restart

Date: 2026-11-10
Status: complete

- After all reviewed branches were merged, restarted East from the merged `main` commit `083ca86` in a dedicated local worktree.
- Stopped the prior Node/Vite listener on loopback port 5173 and Java/Spring listener on loopback port 8081 after confirming both belonged to the existing East checkout.
- Started the backend using the merged `backend/pom.xml` while reusing the existing local configuration and key path; it bound to `127.0.0.1:8081`. The command set Gmail sync initial delay and interval, and daily-report initial delay and poll interval, to 31,536,000,000 ms to prevent automatic mailbox/provider work during this restart. The running JVM command line confirmed these settings.
- Liquibase reported the database was up to date with no changesets executed.
- Installed locked frontend dependencies offline in the main worktree and started Vite on `127.0.0.1:5173` with its API proxy targeting `http://127.0.0.1:8081`.
- Local readiness checks: `/` returned HTTP 200; unauthenticated `/api/v1/dashboard` through the Vite proxy returned HTTP 401. Listener inspection confirmed both services bound to loopback.
- The Maven startup compiled application and test source files but did not run tests. No browser flow, sign-in, Gmail request, or AI-provider request was performed.


## Browser annotation batch restart — 2026-11-10

- After all seven short-lived feature branches passed independent review, their changes were squash-merged into local main at 1555c74 and the task branches/worktrees were removed. The 22 previously registered worktrees were left in place.
- The frontend production build from the main worktree passed: TypeScript compilation succeeded and Vite transformed 2,083 modules.
- The first backend startup attempt from the separate main worktree did not import the root owner-only .env file; PostgreSQL authentication failed before Liquibase connected, so no changesets or application data were changed by that attempt. Retried by importing the existing root .env as a properties file without printing or copying its values.
- The successful Maven Spring Boot startup compiled 146 application source files and 37 test source files, but ran no tests. It bound to 127.0.0.1:8081. Liquibase found all 11 changesets current and executed none.
- Gmail sync initial delay/interval and daily-report initial delay/poll interval were set to 31,536,000,000 ms for this runtime. Vite runs from the same main worktree on 127.0.0.1:5173 and proxies API requests to 127.0.0.1:8081.
- Read-only readiness requests returned HTTP 200 for the frontend root and vault status, and HTTP 401 for the unauthenticated Dashboard API route through the Vite proxy. Listener and working-directory inspection confirmed both processes run from the merged main worktree and bind to loopback.
- git diff --check 05288fa..HEAD passed. No tests, sign-in, mailbox browser flow, Gmail request, AI-provider request, or report generation occurred during this restart.

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

# Conversations Gmail label filters

## Goal

Make Gmail system-label filters such as Important and Unread remain selected and constrain the Conversations list. Keep system labels out of the sidebar section named “User labels.”

## Investigation

- The connected UI builds its label catalog from mailbox label metadata and the current page of thread labels.
- It discarded `SYSTEM` entries from the mailbox catalog, then re-added labels found on current threads with no type. This made system labels appear as if they were user labels.
- Selecting a system label changes the query, which clears the current thread page. The fallback label then disappears from the catalog and the validation effect cleared the selection before the filtered request could run.
- Read-only reproduction in the connected local UI: the Important label click left the selection inactive; the separate Unread quick-filter chip did change the list and returned rows marked unread. No thread was opened and no mailbox mutation occurred.

## Change

- Keep non-category system labels from the mailbox catalog as filter choices.
- Infer known Gmail system labels on fallback row metadata so they stay out of the “User labels” sidebar.
- Keep validation from clearing a filter while label catalogs are still loading or have failed.
- Resolve the active label and dropdown options against the filterable catalog, including system labels.
- The independent review caught a fallback-name ambiguity for title-case user labels. System-label inference now matches Gmail's canonical uppercase names exactly.

## Verification

- `git diff --check` passed.
- `npm --prefix frontend run build` passed: TypeScript build and Vite production build, 2,085 modules transformed. Vite reported the existing large-chunk warning.
- No frontend test runner is configured in `frontend/package.json`.
- A second browser verification against the branch was not available before restart: its isolated Vite tab reached the demo vault-unavailable screen, and the configured backend port 8081 was not listening in the shell environment.
- Independent review and same-reviewer re-review passed with no blocking findings.
- Post-merge restart: PostgreSQL was healthy and left running. Stopped the old East Vite process and the two East Java/Maven processes from the primary checkout; the old backend had been listening on port 8080 and held the singleton lock. Restarted from the clean main worktree with `EAST_SERVER_PORT=8081 EAST_GOOGLE_REDIRECT_URI=http://localhost:8081/login/oauth2/code/google mvn spring-boot:run` and `EAST_API_TARGET=http://127.0.0.1:8081 npm run dev`.
- Spring Boot compiled 146 sources, started on 127.0.0.1:8081, and reported Liquibase changesets 001–011 current. Vite started on 127.0.0.1:5173. Loopback responses: frontend `/` returned HTTP 200; backend `/api/v1/dashboard` returned HTTP 401 without a session; the same request through the Vite proxy returned HTTP 401.
- The backend restart cleared its in-memory sign-in session; a fresh Google sign-in may be required. No Gmail mutation or send was performed.

## Safety

No message bodies, attachments, OAuth credentials, or mailbox-derived secrets were added to these notes. No Gmail mutation or send was performed.

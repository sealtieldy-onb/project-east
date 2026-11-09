# Draft reconciliation UI workstream

## Objective

Wire frontend draft parsing and Composer recovery to the durable draft reconciliation endpoints. Server status is authoritative; the existing session tab lock is only an additional fail-closed guard while status is unknown.

## Status

Implemented. Pending list rows load `GET /api/v1/drafts/{id}/reconciliation` before showing any resolution controls. Mutations stay paused while the selected draft is server-pending. Resolution choices require explicit folder-check attestations and call the server POST endpoint.

Follow-up: a 404 after a `SEND_PENDING`/SEND reconciliation path now preserves a locked uncertain-send recovery panel. It tells the user the send may have completed and its response may have been lost, directs them to check Gmail Sent, and explicitly says not to retry or send another copy. It does not expose resolution choices or normal draft actions when the server draft is missing. A 404 for other draft states keeps the ordinary missing-draft behavior.

## Files

- `frontend/src/lib/drafts-api.ts`
- `frontend/src/components/Composer.tsx`
- `frontend/src/components/composer.css`
- `docs/agent-work/draft-reconciliation-ui.md`

## Behavior and contract

- Draft state parsing includes `SAVE_PENDING` and `SEND_PENDING`; list parsing includes `reconciliationOperation` and `reconciliationStartedAt`.
- Reconciliation status and response payloads are validated, including `inProgress`. GET status uses the authenticated same-origin API; POST resolution includes CSRF headers and `{confirmed: true, resolution, gmailDraftId?}`.
- Selecting a draft first reads server reconciliation status. A pending response is shown directly without calling normal `GET /api/v1/drafts/{id}`, which returns 409 while pending. A non-pending response clears any stale local tab lock and loads the current draft normally.
- Pending drafts have no edit, save, attachment, delete, review, or send controls. The user must attest that they checked Gmail before resolution buttons appear.
- When the server reports `inProgress: true`, neither resolution choice is shown. The panel says the Gmail action is still running and offers a manual status reload.
- A completed SAVE requires the user to confirm finding the matching Gmail Draft. Gmail Draft ID is optional because the backend searches up to 50 recent drafts for a unique exact content/attachment match; the ID can disambiguate a match and is described as available from the opened Gmail draft URL.
- A completed SEND choice explicitly requires the user to attest that they found the message in Gmail Sent. A not-completed SEND from a previously saved Gmail draft also requires attestation that the original draft remains in Gmail Drafts.
- Successful SAVE or restore refreshes the current draft readback and list. Successful SEND removes the draft, refreshes the list, calls the sent callback, and closes the Composer.
- The session storage lock remains only as an extra guard while status cannot be read. It never enables a resolution; only server-confirmed pending status exposes resolution choices. A successful non-pending server read clears the local lock.

## Verification

- `npm run build` in `frontend/` passed after the `inProgress` status handling update (`tsc -b`; Vite v8.3.6, 2,077 modules transformed; JS 414.21 kB, gzip 117.81 kB; CSS 125.59 kB, gzip 20.14 kB).
- `npm run build` in `frontend/` passed after the uncertain-send 404 handling update (`tsc -b`; Vite v8.3.6, 2,077 modules transformed; JS 415.04 kB, gzip 118.04 kB; CSS 125.59 kB, gzip 20.14 kB).
- No tests, backend changes, Gmail API calls, or Google authorization were performed.

## Handoff

- Backend contract source: `docs/agent-work/drafts.md` and the draft reconciliation DTO/controller implementation.
- No project progress log or backend files were changed.

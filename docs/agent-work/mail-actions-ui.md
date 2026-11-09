# Conversations mailbox actions UI workstream

## Objective

Wire the authenticated, connected Conversations view to East's mailbox action and user-label endpoints while keeping demo mode visual-only and requiring an explicit click for every mutation.

## Status

Implemented and verified with the frontend production build. Follow-up label catalog integration is complete. Current review fixes allow expired snippets to be null, keep bulk selections within the backend's 100-thread limit, preserve each selection's mailbox across loaded pages, and prevent cross-mailbox bulk label requests.

## Files

- `frontend/src/components/Conversations.tsx`
- `frontend/src/components/conversations.css`
- `frontend/src/lib/mail-actions-api.ts`
- `frontend/src/lib/conversations-api.ts`
- `docs/agent-work/mail-actions-ui.md`

## Behavior

- Thread opening remains a read-only fetch; it does not mark a message read. Single-thread controls support archive, trash, read/unread, spam/not-spam, star/unstar, and adding/removing user labels. The row star is also a separate explicit action.
- Each live thread row uses sibling buttons for opening, selection, and starring, with no nested interactive elements. Client selection is capped at 100, matching the backend limit; attempts to exceed it are rejected with visible feedback before any request. Selection state stores each selected thread's mailbox ID so label ownership checks work across loaded pages. Bulk actions send only the chosen thread IDs and display an outcome for every selected conversation. Failed items remain selected with their mailbox IDs when some bulk actions succeed.
- Bulk actions include archive, trash, read, unread, spam, not-spam, and adding/removing a chosen user label.
- The label manager supports create, rename, and delete through local authenticated API routes. When All accounts is selected, the manager requires a mailbox choice. The bulk label picker groups labels by mailbox in the combined view.
- Connected Conversations now loads the full label catalog from `GET /api/v1/mailboxes/{mailboxId}/labels` for every connected mailbox with at most three requests in flight. Requests are canceled when the mailbox set changes or the view unmounts; each returned label is explicitly associated with the mailbox requested. Create, rename, and delete refresh only the affected mailbox catalog.
- Catalog `SYSTEM` labels remain available in label filters and message display. Only `USER` labels appear in apply/remove, rename, or delete controls; newly created/renamed labels are modeled as `USER`.
- Label catalog loading and failure states are visible in the label manager and sidebar. Failed mailbox catalogs can be retried from the manager.
- State-changing requests use `getCsrfHeaders()`, same-origin credentials, and no-store caching. No Gmail API calls are made directly from the frontend.
- Loading, success, partial-success, and error states are visible and screen-reader announced. Local thread views refresh after successful changes.
- Demo Conversations remains unchanged and keeps its visual-only controls.

## Assumptions and limitations

- The label catalog endpoint returns local label UUID, name, color, and `SYSTEM`/`USER` type; the client binds every item to the mailbox ID used for the request because the response does not carry mailbox ID.
- The backend remains authoritative for user-label validation and mailbox ownership. In All accounts view, bulk label changes are disabled with an account-specific explanation until every selected conversation belongs to the chosen label's mailbox. The client also validates the selection before submitting.
- The read API may return a null snippet after its encrypted cache expires. The client now accepts an explicit null so metadata-only thread rows can render their existing “Preview is not available” fallback.

## Verification

- `npm run build` passed after the label catalog follow-up in `frontend/` (`tsc -b` and Vite production bundle; 2,074 modules transformed).
- `npm run build` passed after the review fixes in `frontend/` (`tsc -b` and Vite production bundle; 2,077 modules transformed).
- `npm run build` passed after retaining selected mailbox IDs across pages in `frontend/` (`tsc -b` and Vite production bundle; 2,077 modules transformed).
- No Gmail accounts, live Gmail calls, or tests were used.

## Handoff

- Files changed for the review fixes: `frontend/src/components/Conversations.tsx`, `frontend/src/components/conversations.css`, `frontend/src/lib/conversations-api.ts`, and this scratchpad.
- No backend or shared project-progress files were changed. Demo mode remains on the existing visual-only path.

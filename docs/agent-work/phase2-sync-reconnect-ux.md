# Phase 2 sync failure and reconnect UX

## Finding

`GoogleAccessTokenService.getValidAccessToken` returns HTTP 403 when an owned mailbox lacks Gmail's required `gmail.modify` scope (`backend/src/main/java/com/east/app/auth/GoogleAccessTokenService.java:48-50`). Manual sync returns the safe `auth_http_403` failure code, while the durable mailbox status remains generic retryable `error`. The dashboard previously showed only “Sync failed · refresh to retry” for this case, despite the global sync notice advising the user to reconnect.

## Change

- `frontend/src/App.tsx` remembers mailbox IDs that received `auth_http_403` for the current local session, merges them with durable 401 reauthorization state, and clears the hint after a successful sync, when a mailbox is removed, or when the local app session is cleared.
- `frontend/src/components/ConnectedDashboard.tsx` shows “Gmail permission needs approval” on the affected mailbox row and exposes its direct Reconnect action. Other sync errors, including Gmail API configuration failures and rate limits, keep their retry guidance and Refresh action.
- Gmail API HTTP 403 failures remain retryable unless the local token contract specifically reports `auth_http_403`; the generic Google API denial guidance no longer tells users to reconnect every 403.
- No backend/Gmail behavior changed. The 403-specific hint is intentionally session-only because the mailbox summary API does not persist or expose the failure code.

## Verification

- `npm run build` from `frontend/` — passed; `tsc -b` completed and Vite transformed 2,078 modules, then emitted the production assets.
- `git diff --check -- frontend/src/App.tsx frontend/src/components/ConnectedDashboard.tsx` — passed.
- No Gmail/browser/account-content access and no backend test/build were performed.

## Remaining coverage

The frontend 403 permission-reconnect path was verified by code review and build only; it was not exercised against a live mailbox. After a full page reload, a mailbox with only this 403 condition returns to generic persisted `error` until sync is attempted again, because the backend summary intentionally stores only generic health status.

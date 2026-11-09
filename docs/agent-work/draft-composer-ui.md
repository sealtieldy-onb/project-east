# Draft composer UI workstream

## Objective

Implement the Phase 2 connected-mail composer for new messages, replies, reply-all, and forwards. Keep app-local drafts and staged files encrypted until the user explicitly saves to Gmail, sends after a message-specific review, or deletes the draft. Keep the fictional demo path visual-only.

## Status

Implemented and updated after source review. The frontend production build passes with the review fixes and the concurrent app-shell follow-up included.

## Files

- `frontend/src/components/Composer.tsx`
- `frontend/src/components/composer.css`
- `frontend/src/components/Conversations.tsx`
- `frontend/src/components/conversations.css`
- `frontend/src/lib/drafts-api.ts`
- `docs/agent-work/draft-composer-ui.md`

## Behavior

- The connected Conversations header opens a new-message composer. It uses the current mailbox selection; when the scope is All accounts, the user must choose the sending mailbox before creating a draft.
- Thread reading offers Reply, Reply all, and Forward. Replies use the latest incoming message when available; forwards use the latest thread message. The backend builds reply headers, quote text, recipient defaults, and forwarded attachment copies.
- Drafts are saved locally as the user edits. Existing local and saved Gmail drafts can be reopened from the composer. Local draft attachments are selected by the user, staged through the local API, and displayed with remove controls; forwarded attachments arrive in the draft by default and can also be removed.
- The editor supports bold, italic, underline, links, and ordered/unordered lists. User-entered recipients support comma, semicolon, or newline separation across To, CC, and BCC.
- Save to Gmail Drafts is a separate click. A successful save receipt immediately changes the UI to the read-only Gmail-draft state, then the composer reads the current Gmail draft back for review. If readback fails, stale local editing and save actions stay unavailable; the Review action must fetch current Gmail content before sending.
- Sending opens a review of the current From account, To/CC/BCC, subject, complete message body, and attachments. A separate “Send this exact message” click posts the review fingerprint and `updatedAt`; the backend verifies both before sending.
- If Gmail may have accepted a save or send but East cannot reconcile the result, a prominent warning blocks editing, saving, sending, deletion, and closing. The user can reload draft status, check Gmail Sent/Drafts, and explicitly confirm before resuming. The unresolved draft ID is kept in session storage so closing/reopening the composer in the same tab does not silently unlock it.
- Delete requires a second confirmation click for either local or Gmail drafts. Closing the composer flushes pending local edits when possible. The demo-only Conversations path is unchanged.
- Rich HTML is parsed in an inert template, allowlist-sanitized before paste/drop insertion, and re-sanitized for editor readback and final review. Plain-text paste stays text and preserves line breaks. Dropped files are rejected with guidance to use Attach files. No Gmail API calls are made from the frontend.

## API mapping

- `GET /api/v1/drafts` — list app-local and Gmail-saved draft records for the picker.
- `GET /api/v1/drafts/{id}` — load a draft; the send-review path always fetches a fresh response. Saved Gmail draft readback supplies the displayed content, `fromAddress`, and opaque `reviewFingerprint`.
- `POST /api/v1/drafts` — create an encrypted local draft with `mailboxId`, `kind`, and source thread/message IDs for reply/forward drafts.
- `PUT /api/v1/drafts/{id}` — autosave local recipients, subject, and body.
- `POST /api/v1/drafts/{id}/attachments` and `DELETE /api/v1/drafts/{id}/attachments/{attachmentId}` — stage or remove files from the encrypted local draft.
- `POST /api/v1/drafts/{id}/save-to-gmail` — explicit Gmail Drafts save returning `{ id, state, gmailDraftId, savedAt }`; the UI transitions state from this receipt, then reads back current Gmail content before sending.
- `POST /api/v1/drafts/{id}/send` — explicit final send with `{ confirmed: true, expectedUpdatedAt, reviewFingerprint }`.
- `DELETE /api/v1/drafts/{id}` — user-confirmed local or Gmail draft deletion.

## Verification

- `npm run build` passed in `frontend/` (`tsc -b` and Vite production bundle; 2,077 modules transformed) after the review fixes and concurrent app-shell changes.
- No tests or live Gmail/account calls were run.

## Notes

- The frontend expects the coordinated Drafts API contract reported by the backend workstream: `DraftResponse` contains `fromAddress` and `reviewFingerprint`; saved draft GET performs a current Gmail readback; send accepts the fingerprint along with the expected revision timestamp. The exact contract matched the frontend request/response model during coordination.
- Post-side-effect reconciliation errors use the sanitized `DRAFT_RECONCILIATION_REQUIRED` error code. The client also treats an unconfirmed 502 or lost mutation response conservatively and pauses the draft until the user checks Gmail.
- Attachment type/size limits are mirrored in the UI for immediate feedback; the backend remains authoritative.

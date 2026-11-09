# Phase 2 draft/composition source audit

Date: 2026-11-09
Scope: Read-only review of manual composition, encrypted app-local drafts, save to Gmail Drafts, forwarding and attachments, explicit send, and uncertain-operation reconciliation. Compared against `docs/product-plan.md` and `docs/technical-plan.md` / `docs/implementation-roadmap.md`.

## Source findings

- Manual new-message composition selects a sending mailbox before creating the local draft. Reply, Reply all, and Forward bind to the selected message's mailbox. Composer fields include To/CC/BCC; basic rich text, attachment selection/removal, and forwarded attachments are present. Forward attachments are staged by an explicit Forward action and included by default. The app supports up to 10 files, 10 MiB per file, and 16 MiB total; unsafe/missing declared MIME types are normalized to `application/octet-stream`.
- Local draft content and staged attachment bytes are encrypted in storage, ownership-scoped by the app user, and attachments are removed when Gmail save succeeds, the app draft is deleted, or send succeeds. Unsaved local drafts are not tied to body-cache expiry. Closing/switching drafts persists local edits; new composition requires an explicit “Start local draft” after account choice.
- “Save to Gmail Drafts” is a separate explicit action. It transitions the record only after Gmail returns a draft ID and removes the local encrypted payload/attachments. A saved draft is read back from Gmail; review uses the current Gmail copy.
- Sending has a separate review pane displaying sending mailbox, To/CC/BCC, subject, message body, and attachment names/sizes, followed by the explicit “Send this exact message” control. Backend requires `confirmed`, the reviewed update timestamp, and a matching review fingerprint covering payload and attachment contents. For a saved Gmail draft, East re-reads the draft, verifies the fingerprint and attachment bytes, then supplies the Gmail draft ID and replacement `message.raw` built from that same reviewed snapshot to `drafts.send`. Successful sends remove the local draft record and staged files.
- Gmail save/send operations persist a pending state before calling Gmail. If the result is uncertain, East blocks editing/retry and provides reconciliation: the user must attest they checked Gmail Drafts or Sent; save reconciliation verifies a content match (searching up to 50 recent drafts, or by user-provided exact draft ID); send reconciliation either finalizes after user confirmation or restores the draft only after checking any pre-existing Gmail draft. There is no automatic retry path.
- Delete is explicit and two-step in the UI. Deleting an app-local draft removes it locally; deleting a saved Gmail draft also calls Gmail's draft deletion endpoint. Mailbox disconnect cascades app-local drafts/files through the mailbox foreign key; saved Gmail content is not deleted by disconnect.
- No source-level mismatch was found in the audited flows against the selected Phase 2 requirements. This is a source review, not runtime acceptance.

## User/runtime acceptance still unverified

- No manual new email, reply, reply-all, or forward has been created in a connected mailbox during this audit.
- No user-selected file has been staged, removed, saved, or sent; attachment runtime checks were previously deferred by the user.
- Gmail Drafts save/read-back/delete, explicit send, and uncertain save/send reconciliation have not been exercised. Do not infer success from source review and do not run Gmail side effects without the user's action.
- The user previously verified Star/Unstar. This audit made no Gmail UI interaction or Gmail API call.

## Work performed

- Read product/technical roadmap requirements and the draft controller, service, repository, migrations, source-message/forwarding path, MIME builder, and React composer.
- At audit time no app code changed and no tests/builds were run.

## Synthetic encryption verification follow-up — 2026-11-09

- Added in-memory persistence-boundary coverage for `DraftService.create` and `DraftService.addAttachment`. The tests assert that repository-bound payload and attachment bytes are ciphertext and that decryption requires the correct associated user/draft/mailbox or attachment IDs.
- At audit time, `mvn -o -B -f backend/pom.xml test` passed all 10 backend tests. Follow-up send-race coverage added two synthetic tests; the latest full suite passed all 12 tests. These tests use synthetic values and do not use the configured database or call Gmail.
- This does not count as live draft acceptance. No actual draft was saved to Gmail, no real attachment was loaded into the app, and no send or reconciliation flow was performed. The user's attachment-check deferral remains in effect.
- The saved-draft review/send race was closed in source by sending the fingerprinted snapshot as a replacement message in the same Gmail `drafts.send` operation. The payload and inline-byte retention tests passed; live send behavior remains unverified until the user performs the explicit reviewed send.

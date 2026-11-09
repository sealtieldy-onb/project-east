# Saved Gmail draft send review binding

## Objective

Make a confirmed send of a saved Gmail draft transmit exactly the message content East re-read and matched to the user's review fingerprint. Gmail can be edited by another client while East is open, so sending by stored draft ID alone left a race between East's final GET and Gmail's send operation.

## Implementation

- `DraftService.prepareSend` now reads the saved draft once, parses the user-visible fields, fetches and hashes attachment bytes, checks the review fingerprint, and builds a MIME message from that same snapshot.
- For inline draft attachments, `DraftGmailReviewParser` retains bytes only for this send preparation path; ordinary review continues to hash then wipe inline bytes. Send preparation transfers the bytes to `PreparedSend`, whose cleanup wipes them on success or failure.
- `GmailApiClient.sendDraft` now supplies the Gmail draft ID and the reviewed replacement message (`message.raw` plus thread ID) in one `drafts.send` request. This uses Gmail's documented option to update a draft in the send request, so a concurrent Gmail edit cannot substitute different content after East's review.
- The local-draft `messages.send` path is unchanged. Existing explicit confirmation, revision, fingerprint, durable pending state, and no-retry reconciliation protections remain in force.

## Verification

- Added a synthetic parser test proving inline attachment bytes are retained only for send preparation and can be wiped afterward.
- Added a request-shape test proving `drafts.send` carries both the draft ID and reviewed replacement message.
- `mvn -o -B -f backend/pom.xml test` passed all 12 tests (0 failures/errors/skips) on 2026-11-09.
- No Gmail call, live draft, attachment, or mailbox mutation was used. Runtime send acceptance still needs the user to exercise the explicit reviewed-send control.

## Notes

Gmail's API documents that `drafts.send` can receive the draft ID and replacement `messages.raw` in the same request. The message is therefore generated from the snapshot that East just parsed and fingerprinted. This narrows the concurrency issue by making the reviewed content the content sent, even if the stored Gmail draft is edited concurrently.

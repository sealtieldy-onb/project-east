# Gmail sync service scratchpad

## Objective and scope

Implement a read-only mailbox synchronization worker under `backend/src/main/java/com/east/app/gmail/`. First sync caches the latest 30 messages; subsequent syncs consume Gmail History, with a 404 stale-cursor fallback to a new bounded sync. Poll every 15 minutes only while the local vault is unlocked. No OAuth/controller/client/migration edits, Gmail mutations, attachment-byte fetches, or content/token logging.

## Status

Implementation complete; static source review performed. Existing Gmail API client, vault encryption service, schema, and access-token service contract were inspected. Schema supports the requested cache/cursor/label/attachment fields; recipients and detail-only headers are inside the encrypted message payload per parent direction. No migration was needed.

## API and persistence assumptions

- `GoogleAccessTokenService` is in `com.east.app.auth` and `getValidAccessToken(UUID appUserId, UUID mailboxId)` returns a `String` access token. Never log it.
- GmailApiClient methods used are read-only: `getProfile`, `listMessages`, `getMessage`, `listHistory`, and `listLabels`.
- `connected_mailbox.sync_cursor` stores Gmail `historyId`; successful persistence sets `last_sync_at` from injected `Clock`. A missing cursor triggers initial bounded sync.
- `app_user.body_cache_ttl_days` determines body and snippet expiry. Metadata remains after the encrypted bodies/snippets expire.
- Labels are upserted by `(mailbox_id, gmail_label_id)`; each fetched message's join rows are replaced from its current Gmail `labelIds`. Labels no longer returned by Gmail are removed, cascading their old join rows. Gmail system categories map by ID for UI categorization: `CATEGORY_PERSONAL` → `PRIMARY`, while `CATEGORY_SOCIAL`, `CATEGORY_PROMOTIONS`, `CATEGORY_UPDATES`, and `CATEGORY_FORUMS` map to `SOCIAL`, `PROMOTIONS`, `UPDATES`, and `FORUMS`. The schema stores raw Gmail IDs, so no derived category column is written. Gmail `UNREAD` and `STARRED` labels derive thread-level read/starred state.
- Attachment rows are upserted by `(message_id, gmail_attachment_id)` with filename, MIME type, and optional byte size. Metadata absent from a refreshed message is removed, while matching rows retain any separately cached encrypted bytes. Initial sync stores metadata with null content/fetched/expiry fields; no attachment-byte method is called.
- The schema has no label-color or durable sync-state columns. Label color is omitted; readiness can be inferred from successful `last_sync_at`, and failures are isolated/returned as sanitized outcomes.

## Encrypted message payload and AAD

`mail_message.body_ciphertext` contains UTF-8 JSON serialized with Jackson and encrypted via `VaultService.encryptLocal`. Current payload version:

```json
{
  "schemaVersion": 1,
  "bodyText": "plain text or null",
  "bodyHtml": "html or null",
  "bodyMimeType": "text/plain or text/html or null",
  "headers": {
    "from": "...",
    "to": "...",
    "cc": "...",
    "bcc": "...",
    "replyTo": "...",
    "messageId": "...",
    "inReplyTo": "...",
    "references": "..."
  }
}
```

`bodyText` and `bodyHtml` are extracted from text MIME parts and base64url-decoded as UTF-8. When multiple parts share a type, the current implementation joins them with a newline. The selected encrypted headers are `from`, `to`, `cc`, `bcc`, `reply-to`, `message-id`, `in-reply-to`, and `references`; date and subject are intentionally omitted from this private payload because their approved navigation values are stored in the existing timestamp and subject columns. The encrypted payload is stored with `body_mime_type = 'application/json'` because its ciphertext contains the versioned JSON envelope.

The exact associated-data builders in `GmailSyncService` return UTF-8 bytes from these templates. UUID values use Java's canonical lowercase `UUID.toString()` form, and Gmail IDs are inserted as returned by Gmail without decoding or trimming:

- Message: `east:gmail-message-cache:v1:user:<appUserId>:mailbox:<mailboxId>:thread:<gmailThreadId>:message:<gmailMessageId>`
- Thread snippet: `east:gmail-thread-snippet:v1:user:<appUserId>:mailbox:<mailboxId>:thread:<gmailThreadId>`

Only `mail_message.sender_email`, `sender_name`, `sent_at`, thread subject/time/status/labels, and attachment metadata are plaintext navigation metadata. Recipient/detail headers and body contents stay in the encrypted JSON. Snippet text uses separate ciphertext and the thread-scoped AAD above. Temporary serialized content bytes are wiped after encryption; encrypted payload byte arrays are destroyed after persistence (or on a failed fetch/persist).

## Plan

1. Add a JDBC repository for mailbox cursor/TTL lookup and transactional persistence of message metadata, encrypted payloads, labels, attachment metadata, and cursor/time.
2. Add a scheduler/service that skips while locked, isolates mailboxes, captures a pre-list history cursor for initial sync, and handles paginated History updates.
3. Update this scratchpad with verification and report controller/service contracts to the parent.

## Verification

- Inspected both new Java files, the existing OAuth token service and Gmail API client, VaultService, and Liquibase schema.
- Maven/build/runtime verification was not run per parent instruction.

## Handoff

## Files and handoff

- `backend/src/main/java/com/east/app/gmail/GmailSyncService.java`: scheduled read-only sync, initial latest-30 baseline, history pagination/stale-cursor fallback, MIME content parsing/encryption, and per-mailbox outcomes.
- `backend/src/main/java/com/east/app/gmail/GmailSyncRepository.java`: transactional message/thread/label/attachment metadata persistence and cursor advancement.
- Parent can call `syncMailbox(appUserId, mailboxId)` for a user-requested endpoint, or `syncConnectedMailboxes()` for an all-mailbox run. A successful `MailboxSyncResult` carries `syncedAt`; failures expose only a safe `errorCode`. The current per-mailbox `sync_status` is the durable UI state; `last_sync_at` records the most recent successful cursor/content commit.

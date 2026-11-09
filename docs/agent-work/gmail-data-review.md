# Gmail data path security review

## Objective

Source review of Gmail synchronization, search/thread reads, mailbox actions and labels, attachment retrieval, retention, and mailbox ownership. The follow-up task authorizes only the memory bounds documented below. Do not read `.env`, initiate OAuth, call Gmail, or run tests/builds.

## Findings

### P2 — Sync can accumulate unbounded Gmail payloads in memory

- Initial sync limits the number of messages to 30 (`GmailSyncService.java:47, 216-250`), but not the size of each full message. Incremental history reads every history page and accumulates every changed message ID without a page, count, or byte ceiling (`GmailSyncService.java:284-315`), then fetches all full messages into one `List<CachedMessage>` before persisting (`GmailSyncService.java:345-382`).
- On-demand thread hydration fetches the entire full thread and accumulates all message bodies before persisting (`GmailSyncService.java:120-170`). Body parts are base64-decoded and concatenated without a content-size ceiling (`GmailSyncService.java:399-410, 525-558`). The REST client parses the full JSON response into a `JsonNode` (`GmailApiClient.java:267-280`).
- These payloads are untrusted email content. A large message/thread or a large history backlog can cause a substantial heap spike or exhaust the local backend process, even though the first sync is bounded by message count. Consider bounding per-message decoded body size and total messages/bytes per sync batch, with cursor-safe continuation; avoid holding whole-thread/full-history payloads at once.

## Reviewed behavior

- Scheduled sync runs at a 900,000 ms fixed delay and exits when the vault is locked; initial sync enumerates at most 30 recent message IDs (`GmailSyncService.java:76-88, 216-250`). Sync reads Gmail state and writes local cache; the reviewed scheduler and sync client do not call mailbox mutation or send operations.
- Thread detail fetches Gmail metadata and hydrates expired/partial content without calling a mark-read API (`MailReadService.java:128-180`; `GmailSyncService.java:120-170`). Search is on-demand, binds server-side cursors to the current app-user/request key, gives each cursor a random UUID, and expires it after 15 minutes (`MailReadService.java:197-245, 622-655, 699-749`).
- Read and action repositories scope mailbox/thread/label lookups to the app-user ID. Attachment lookup ties the app user, mailbox, thread, message, and attachment together before retrieving Gmail bytes (`MailReadRepository.java:127-139`; `MailActionsRepository.java:21-81`; `AttachmentRepository.java:23-60`).
- Gmail mailbox mutations are reached through explicit authenticated action/label controller routes (`MailActionsController.java:28-68`; `MailActionsService.java:38-42, 71-130, 132-212`). The reviewed non-draft routes contain no send calls; Gmail send operations are in the separate draft service and were outside this audit.
- Attachment bodies are rejected when metadata advertises more than 50 MiB, checked again after Gmail returns bytes, encrypted with owner/mailbox/thread/message/attachment associated data, and returned only to the authenticated user with `Cache-Control: no-store` (`AttachmentContentService.java:48-100, 135-164`; `AttachmentAad.java`; `AttachmentContentController.java:30-47`).
- TTL cleanup deletes expired attachment records/bytes, clears message bodies and snippets while retaining message navigation metadata, and runs on a 60-second interval independent of vault unlock (`RetentionCleanupService.java:24-33`; `RetentionCleanupRepository.java:49-83`). Changing retention updates live attachment/body expiries (`SettingsService.java:137-179`).
- Message HTML is sanitized before API output; image/PDF previews are allowlisted in the UI, and PDFs render inside a sandboxed iframe (`MailHtmlSanitizer.java:8-27`; `frontend/src/components/Conversations.tsx:571-587, 619-640`).

## Scope and verification

- Source inspection only. `.env` was not accessed; no Google/Gmail requests, tests, or builds were run.
- The review did not exercise OAuth, Gmail API behavior, database data, or runtime attachment limits.

## Follow-up — bounded sync and message hydration

Implemented conservative memory limits in the Gmail data path, without a schema change:

- Regular Gmail JSON responses are read with an 8 KiB scratch buffer and capped at 16 MiB before Jackson parsing. Content-Length is rejected early when it exceeds the cap. The response scratch buffer is wiped after use. Oversized full messages retry in metadata format; their encrypted cache retains headers only and carries a narrow reason enum. Oversized threads retry in metadata format and report the thread limit. Error JSON is capped at 64 KiB. Gmail attachment JSON has its own 68 MiB cap to allow base64 expansion for the existing 50 MiB decoded attachment limit; decoded attachment bytes are checked against 50 MiB before return (`GmailApiClient.java`).
- Text/plain and text/html bodies have a 1 MiB decoded-byte ceiling per message, a 4 MiB ceiling per thread hydration, and an 8 MiB aggregate ceiling per initial sync/history page. During a paginated history run, per-thread usage carries across pages and at most 5,000 thread budgets are tracked; messages from additional threads remain metadata-only. Body lengths are estimated from base64 before decoding, so an oversized body is omitted whole; its metadata remains. Body bytes are wiped after UTF-8 conversion. Header values are omitted above 8 Ki characters and snippets are capped at 1,024 characters (`GmailSyncService.java`).
- Initial sync still processes at most 30 messages. History sync now handles one history API page at a time, rejects pages with more than 5,000 changed IDs or 5,000 deleted IDs, fetches message changes in chunks of 50, and deletions in chunks of 500. Content chunks commit idempotently while retaining the old sync cursor. Only after the final API page (no nextPageToken) and all its chunks persist does East advance to that final page's historyId; a failure replays work from the old cursor (`GmailSyncService.java`, `GmailSyncRepository.java`). Vault state is checked immediately before on-demand persistence and final cursor advancement.
- When a body is omitted for a permanent local message/thread limit, the database stores an internal non-content reason (`MESSAGE_TOO_LARGE`, `THREAD_LIMIT`, or `SYNC_LIMIT`) in the existing MIME-type field and the encrypted body payload contains headers only. The API exposes only that enum, never the internal database marker. A sync-batch omission expires immediately to trigger bounded on-demand hydration. If an existing complete body is still unexpired, a metadata-only retry preserves it. Incomplete metadata fallback does not delete known attachment metadata (`GmailSyncRepository.java`, `MailReadRepository.java`, `MailReadService.java`, `MailReadModels.java`).
- Conversations renders an explicit East local display/cache limit message for the narrow unavailable-reason enum; ordinary cache expiry keeps the existing “not available in the local cache” message (`conversations-api.ts`, `Conversations.tsx`).

The parent agent compiled the final backend source (113 Java sources), applied Liquibase changeset 003, and restarted the local service. No `.env` access, Gmail requests, or tests were performed. The limits are source-reviewed and compile-checked but have not been exercised against a live Gmail account.

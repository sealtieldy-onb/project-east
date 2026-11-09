package com.east.app.gmail;

import com.east.app.vault.EncryptedPayload;
import com.east.app.auth.MailboxSyncStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JDBC persistence for Gmail sync metadata and encrypted message caches. */
@Repository
public class GmailSyncRepository {

    private final JdbcTemplate jdbcTemplate;

    public GmailSyncRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<SyncTarget> findAllTargets() {
        return jdbcTemplate.query(
                "SELECT app_user_id, id, sync_cursor FROM connected_mailbox ORDER BY created_at, id",
                this::mapTarget);
    }

    public Optional<SyncTarget> findOwnedTarget(UUID appUserId, UUID mailboxId) {
        List<SyncTarget> targets = jdbcTemplate.query(
                "SELECT app_user_id, id, sync_cursor FROM connected_mailbox WHERE app_user_id = ? AND id = ?",
                this::mapTarget,
                appUserId,
                mailboxId);
        return targets.stream().findFirst();
    }

    /** Records an attempt without hiding an already-known reauthorization requirement. */
    public void markSyncing(UUID appUserId, UUID mailboxId) {
        int updated = jdbcTemplate.update(
                """
                UPDATE connected_mailbox
                SET sync_status = CASE
                        WHEN reauthorization_required THEN 'needs_reauthorization'
                        ELSE 'syncing'
                    END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE app_user_id = ? AND id = ?
                """,
                appUserId,
                mailboxId);
        if (updated != 1) {
            throw new IllegalStateException("The mailbox was not available for sync status update.");
        }
    }

    /** Persists only generic retry state; provider details remain in the bounded request response/log path. */
    public void markSyncFailure(UUID appUserId, UUID mailboxId, MailboxSyncStatus status) {
        if (status != MailboxSyncStatus.PARTIAL_FAILURE && status != MailboxSyncStatus.ERROR) {
            throw new IllegalArgumentException("Only retryable sync failure states can be persisted here.");
        }
        int updated = jdbcTemplate.update(
                """
                UPDATE connected_mailbox
                SET sync_status = CASE
                        WHEN reauthorization_required THEN 'needs_reauthorization'
                        ELSE ?
                    END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE app_user_id = ? AND id = ?
                """,
                status.value(),
                appUserId,
                mailboxId);
        if (updated != 1) {
            throw new IllegalStateException("The mailbox was not available for sync status update.");
        }
    }

    public int bodyCacheTtlDays(UUID appUserId) {
        Integer days = jdbcTemplate.queryForObject(
                "SELECT body_cache_ttl_days FROM app_user WHERE id = ?",
                Integer.class,
                appUserId);
        if (days == null || days < 1 || days > 365) {
            throw new IllegalStateException("The configured message cache lifetime is invalid.");
        }
        return days;
    }

    public Set<String> findThreadMessageIds(UUID mailboxId, String gmailThreadId) {
        return new HashSet<>(jdbcTemplate.query(
                """
                SELECT message.gmail_message_id
                FROM mail_message message
                JOIN mail_thread thread ON thread.id = message.thread_id
                WHERE message.mailbox_id = ? AND thread.gmail_thread_id = ?
                """,
                (resultSet, rowNumber) -> resultSet.getString("gmail_message_id"),
                mailboxId,
                gmailThreadId));
    }

    /** Commits all changes for one mailbox and advances its cursor only after persistence succeeds. */
    @Transactional
    public void persist(MailboxSyncBatch batch) {
        persistContent(batch);

        int updated = jdbcTemplate.update(
                """
                UPDATE connected_mailbox
                SET sync_cursor = ?, last_sync_at = ?, sync_status = 'ready', updated_at = CURRENT_TIMESTAMP
                WHERE app_user_id = ? AND id = ?
                """,
                batch.historyCursor(),
                Timestamp.from(batch.syncedAt()),
                batch.appUserId(),
                batch.mailboxId());
        if (updated != 1) {
            throw new IllegalStateException("The mailbox was not available for sync persistence.");
        }
    }

    /** Commits an idempotent history page chunk without moving the mailbox cursor. */
    @Transactional
    public void persistPartial(MailboxSyncBatch batch) {
        persistContent(batch);
    }

    /** Moves the history cursor only after every page and all its content chunks committed. */
    @Transactional
    public void advanceCursor(UUID appUserId, UUID mailboxId, String historyCursor, Instant syncedAt) {
        int updated = jdbcTemplate.update(
                """
                UPDATE connected_mailbox
                SET sync_cursor = ?, last_sync_at = ?, sync_status = 'ready', updated_at = CURRENT_TIMESTAMP
                WHERE app_user_id = ? AND id = ?
                """,
                historyCursor,
                Timestamp.from(syncedAt),
                appUserId,
                mailboxId);
        if (updated != 1) {
            throw new IllegalStateException("The mailbox was not available for sync persistence.");
        }
    }

    /** Persists a user-opened message refresh without changing the scheduled-sync cursor/status. */
    @Transactional
    public void persistOnDemand(MailboxSyncBatch batch) {
        persistContent(batch);
    }

    @Transactional
    public void refreshLabels(UUID mailboxId, List<GmailLabelMetadata> labels) {
        if (labels.isEmpty()) return;
        upsertLabels(mailboxId, labels);
        deleteStaleLabels(mailboxId, labels);
    }

    private void persistContent(MailboxSyncBatch batch) {
        if (!batch.labels().isEmpty()) upsertLabels(batch.mailboxId(), batch.labels());

        for (CachedMessage message : batch.messages()) {
            UUID threadId = upsertThread(batch, message);
            UUID localMessageId = upsertMessage(batch, threadId, message);
            replaceMessageLabels(batch.mailboxId(), localMessageId, message.gmailLabelIds());
            if (message.attachmentMetadataComplete()) {
                upsertAttachments(localMessageId, message.attachments());
            }
        }

        for (String deletedMessageId : batch.deletedGmailMessageIds()) {
            deleteMessage(batch.mailboxId(), deletedMessageId);
        }

        if (!batch.labels().isEmpty()) deleteStaleLabels(batch.mailboxId(), batch.labels());
        deleteEmptyThreads(batch.mailboxId());
        refreshThreadMetadata(batch.mailboxId());
    }

    private UUID upsertThread(MailboxSyncBatch batch, CachedMessage message) {
        EncryptedPayload snippet = message.encryptedSnippet();
        byte[] snippetNonce = snippet == null ? null : snippet.nonce();
        byte[] snippetCiphertext = snippet == null ? null : snippet.ciphertext();
        Timestamp timestamp = Timestamp.from(message.sentAt());
        Timestamp snippetExpiresAt = snippet == null ? null : Timestamp.from(message.snippetExpiresAt());
        String sql = """
                INSERT INTO mail_thread (
                    id, mailbox_id, gmail_thread_id, subject, last_message_at,
                    message_count, is_unread, is_starred,
                    snippet_nonce, snippet_ciphertext, snippet_expires_at
                ) VALUES (?, ?, ?, ?, ?, 0, FALSE, FALSE, ?, ?, ?)
                ON CONFLICT (mailbox_id, gmail_thread_id) DO UPDATE SET
                    subject = CASE
                        WHEN mail_thread.last_message_at IS NULL
                             OR EXCLUDED.last_message_at >= mail_thread.last_message_at
                        THEN EXCLUDED.subject ELSE mail_thread.subject END,
                    last_message_at = GREATEST(
                        COALESCE(mail_thread.last_message_at, EXCLUDED.last_message_at),
                        EXCLUDED.last_message_at),
                    snippet_nonce = CASE
                        WHEN mail_thread.last_message_at IS NULL
                             OR EXCLUDED.last_message_at >= mail_thread.last_message_at
                        THEN EXCLUDED.snippet_nonce ELSE mail_thread.snippet_nonce END,
                    snippet_ciphertext = CASE
                        WHEN mail_thread.last_message_at IS NULL
                             OR EXCLUDED.last_message_at >= mail_thread.last_message_at
                        THEN EXCLUDED.snippet_ciphertext ELSE mail_thread.snippet_ciphertext END,
                    snippet_expires_at = CASE
                        WHEN mail_thread.last_message_at IS NULL
                             OR EXCLUDED.last_message_at >= mail_thread.last_message_at
                        THEN EXCLUDED.snippet_expires_at ELSE mail_thread.snippet_expires_at END,
                    updated_at = CURRENT_TIMESTAMP
                RETURNING id
                """;
        UUID generatedId = UUID.randomUUID();
        return jdbcTemplate.queryForObject(
                sql,
                UUID.class,
                generatedId,
                batch.mailboxId(),
                message.gmailThreadId(),
                message.subject(),
                timestamp,
                snippetNonce,
                snippetCiphertext,
                snippetExpiresAt);
    }

    private UUID upsertMessage(MailboxSyncBatch batch, UUID threadId, CachedMessage message) {
        EncryptedPayload body = message.encryptedPayload();
        String bodyMimeType = bodyMimeType(message.bodyUnavailableReason(), message.syncLimitRetryOnOpen());
        String sql = """
                INSERT INTO mail_message (
                    id, mailbox_id, thread_id, gmail_message_id, sender_email, sender_name,
                    sent_at, body_mime_type, body_nonce, body_ciphertext, body_cached_at, body_expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (mailbox_id, gmail_message_id) DO UPDATE SET
                    thread_id = EXCLUDED.thread_id,
                    sender_email = EXCLUDED.sender_email,
                    sender_name = EXCLUDED.sender_name,
                    sent_at = EXCLUDED.sent_at,
                    body_mime_type = CASE WHEN EXCLUDED.body_mime_type = 'application/json'
                        OR mail_message.body_nonce IS NULL OR mail_message.body_expires_at <= CURRENT_TIMESTAMP
                        THEN EXCLUDED.body_mime_type ELSE mail_message.body_mime_type END,
                    body_nonce = CASE WHEN EXCLUDED.body_mime_type = 'application/json'
                        OR mail_message.body_nonce IS NULL OR mail_message.body_expires_at <= CURRENT_TIMESTAMP
                        THEN EXCLUDED.body_nonce ELSE mail_message.body_nonce END,
                    body_ciphertext = CASE WHEN EXCLUDED.body_mime_type = 'application/json'
                        OR mail_message.body_nonce IS NULL OR mail_message.body_expires_at <= CURRENT_TIMESTAMP
                        THEN EXCLUDED.body_ciphertext ELSE mail_message.body_ciphertext END,
                    body_cached_at = CASE WHEN EXCLUDED.body_mime_type = 'application/json'
                        OR mail_message.body_nonce IS NULL OR mail_message.body_expires_at <= CURRENT_TIMESTAMP
                        THEN EXCLUDED.body_cached_at ELSE mail_message.body_cached_at END,
                    body_expires_at = CASE WHEN EXCLUDED.body_mime_type = 'application/json'
                        OR mail_message.body_nonce IS NULL OR mail_message.body_expires_at <= CURRENT_TIMESTAMP
                        THEN EXCLUDED.body_expires_at ELSE mail_message.body_expires_at END
                RETURNING id
                """;
        return jdbcTemplate.queryForObject(
                sql,
                UUID.class,
                UUID.randomUUID(),
                batch.mailboxId(),
                threadId,
                message.gmailMessageId(),
                message.senderEmail(),
                message.senderName(),
                Timestamp.from(message.sentAt()),
                bodyMimeType,
                body.nonce(),
                body.ciphertext(),
                Timestamp.from(batch.syncedAt()),
                Timestamp.from(message.bodyExpiresAt()));
    }

    private static String bodyMimeType(BodyUnavailableReason reason, boolean syncLimitRetryOnOpen) {
        if (reason == null) return "application/json";
        return switch (reason) {
            case MESSAGE_TOO_LARGE -> "east-internal:MESSAGE_TOO_LARGE";
            case THREAD_LIMIT -> "east-internal:THREAD_LIMIT";
            case SYNC_LIMIT -> syncLimitRetryOnOpen
                    ? "east-internal:SYNC_LIMIT_ON_DEMAND"
                    : "east-internal:SYNC_LIMIT";
            case ON_DEMAND -> "east-internal:ON_DEMAND";
        };
    }

    private void upsertLabels(UUID mailboxId, List<GmailLabelMetadata> labels) {
        for (GmailLabelMetadata label : labels) {
            jdbcTemplate.update(
                    """
                    INSERT INTO mail_label (id, mailbox_id, gmail_label_id, name, label_type)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (mailbox_id, gmail_label_id) DO UPDATE SET
                        name = EXCLUDED.name,
                        label_type = EXCLUDED.label_type
                    """,
                    UUID.randomUUID(),
                    mailboxId,
                    label.gmailLabelId(),
                    label.name(),
                    label.type());
        }
    }

    private void replaceMessageLabels(UUID mailboxId, UUID localMessageId, List<String> gmailLabelIds) {
        jdbcTemplate.update(
                "DELETE FROM mail_message_label WHERE mailbox_id = ? AND message_id = ?",
                mailboxId,
                localMessageId);
        for (String gmailLabelId : gmailLabelIds) {
            jdbcTemplate.update(
                    """
                    INSERT INTO mail_message_label (message_id, label_id, mailbox_id)
                    SELECT ?, label.id, ?
                    FROM mail_label label
                    WHERE label.mailbox_id = ? AND label.gmail_label_id = ?
                    ON CONFLICT (message_id, label_id) DO NOTHING
                    """,
                    localMessageId,
                    mailboxId,
                    mailboxId,
                    gmailLabelId);
        }
    }

    private void upsertAttachments(UUID localMessageId, List<AttachmentMetadata> attachments) {
        if (attachments.isEmpty()) {
            jdbcTemplate.update("DELETE FROM mail_attachment WHERE message_id = ?", localMessageId);
            return;
        }

        String placeholders = String.join(",", Collections.nCopies(attachments.size(), "?"));
        List<Object> parameters = new ArrayList<>();
        parameters.add(localMessageId);
        for (AttachmentMetadata attachment : attachments) {
            parameters.add(attachment.gmailAttachmentId());
        }
        jdbcTemplate.update(
                "DELETE FROM mail_attachment WHERE message_id = ? AND gmail_attachment_id NOT IN ("
                        + placeholders + ")",
                parameters.toArray());

        for (AttachmentMetadata attachment : attachments) {
            jdbcTemplate.update(
                    """
                    INSERT INTO mail_attachment (
                        id, message_id, gmail_attachment_id, filename, mime_type, byte_size
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (message_id, gmail_attachment_id) DO UPDATE SET
                        filename = EXCLUDED.filename,
                        mime_type = EXCLUDED.mime_type,
                        byte_size = EXCLUDED.byte_size
                    """,
                    UUID.randomUUID(),
                    localMessageId,
                    attachment.gmailAttachmentId(),
                    attachment.filename(),
                    attachment.mimeType(),
                    attachment.byteSize());
        }
    }

    private void deleteMessage(UUID mailboxId, String gmailMessageId) {
        List<DeletedMessage> messages = jdbcTemplate.query(
                "SELECT thread_id, sent_at FROM mail_message WHERE mailbox_id = ? AND gmail_message_id = ?",
                (resultSet, rowNumber) -> new DeletedMessage(
                        resultSet.getObject("thread_id", UUID.class),
                        resultSet.getTimestamp("sent_at")),
                mailboxId,
                gmailMessageId);
        if (messages.isEmpty()) return;
        for (DeletedMessage message : messages) {
            jdbcTemplate.update(
                    """
                    UPDATE mail_thread
                    SET snippet_nonce = NULL,
                        snippet_ciphertext = NULL,
                        snippet_expires_at = NULL
                    WHERE id = ? AND last_message_at <= ?
                    """,
                    message.threadId(),
                    message.sentAt());
        }
        jdbcTemplate.update(
                "DELETE FROM mail_message WHERE mailbox_id = ? AND gmail_message_id = ?",
                mailboxId,
                gmailMessageId);
    }

    private void deleteStaleLabels(UUID mailboxId, List<GmailLabelMetadata> currentLabels) {
        Set<String> currentIds = new HashSet<>();
        for (GmailLabelMetadata label : currentLabels) currentIds.add(label.gmailLabelId());
        List<String> existingIds = jdbcTemplate.query(
                "SELECT gmail_label_id FROM mail_label WHERE mailbox_id = ?",
                (resultSet, rowNumber) -> resultSet.getString("gmail_label_id"),
                mailboxId);
        for (String existingId : existingIds) {
            if (!currentIds.contains(existingId)) {
                jdbcTemplate.update(
                        "DELETE FROM mail_label WHERE mailbox_id = ? AND gmail_label_id = ?",
                        mailboxId,
                        existingId);
            }
        }
    }

    private void deleteEmptyThreads(UUID mailboxId) {
        jdbcTemplate.update(
                """
                DELETE FROM mail_thread thread
                WHERE thread.mailbox_id = ?
                  AND NOT EXISTS (SELECT 1 FROM mail_message message WHERE message.thread_id = thread.id)
                """,
                mailboxId);
    }

    private void refreshThreadMetadata(UUID mailboxId) {
        jdbcTemplate.update(
                """
                UPDATE mail_thread thread
                SET message_count = (
                        SELECT COUNT(*) FROM mail_message message WHERE message.thread_id = thread.id
                    ),
                    last_message_at = (
                        SELECT MAX(message.sent_at) FROM mail_message message WHERE message.thread_id = thread.id
                    ),
                    is_unread = EXISTS (
                        SELECT 1
                        FROM mail_message message
                        JOIN mail_message_label link ON link.message_id = message.id
                        JOIN mail_label label ON label.id = link.label_id
                        WHERE message.thread_id = thread.id AND label.gmail_label_id = 'UNREAD'
                    ),
                    is_starred = EXISTS (
                        SELECT 1
                        FROM mail_message message
                        JOIN mail_message_label link ON link.message_id = message.id
                        JOIN mail_label label ON label.id = link.label_id
                        WHERE message.thread_id = thread.id AND label.gmail_label_id = 'STARRED'
                    ),
                    updated_at = CURRENT_TIMESTAMP
                WHERE thread.mailbox_id = ?
                """,
                mailboxId);
    }

    private SyncTarget mapTarget(ResultSet resultSet, int rowNumber) throws SQLException {
        return new SyncTarget(
                resultSet.getObject("app_user_id", UUID.class),
                resultSet.getObject("id", UUID.class),
                resultSet.getString("sync_cursor"));
    }

    private record DeletedMessage(UUID threadId, Timestamp sentAt) { }

    public record SyncTarget(UUID appUserId, UUID mailboxId, String historyCursor) { }

    public record GmailLabelMetadata(String gmailLabelId, String name, String type) { }

    public record AttachmentMetadata(String gmailAttachmentId, String filename, String mimeType, Long byteSize) { }

    public record CachedMessage(
            String gmailMessageId,
            String gmailThreadId,
            String subject,
            String senderEmail,
            String senderName,
            Instant sentAt,
            Instant bodyExpiresAt,
            Instant snippetExpiresAt,
            EncryptedPayload encryptedPayload,
            EncryptedPayload encryptedSnippet,
            List<String> gmailLabelIds,
            List<AttachmentMetadata> attachments,
            BodyUnavailableReason bodyUnavailableReason,
            boolean syncLimitRetryOnOpen,
            boolean attachmentMetadataComplete) { }

    public enum BodyUnavailableReason {
        MESSAGE_TOO_LARGE,
        THREAD_LIMIT,
        SYNC_LIMIT,
        ON_DEMAND
    }

    public record MailboxSyncBatch(
            UUID appUserId,
            UUID mailboxId,
            String historyCursor,
            Instant syncedAt,
            List<GmailLabelMetadata> labels,
            List<CachedMessage> messages,
            Set<String> deletedGmailMessageIds) { }
}

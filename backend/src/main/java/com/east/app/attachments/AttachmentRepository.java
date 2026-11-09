package com.east.app.attachments;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Ownership-scoped metadata and encrypted cache operations for a single Gmail attachment. */
@Repository
class AttachmentRepository {

    private final JdbcTemplate jdbcTemplate;

    AttachmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    Optional<AttachmentAccessRecord> findOwnedAttachment(
            UUID appUserId,
            UUID threadId,
            UUID messageId,
            UUID attachmentId) {
        return jdbcTemplate.query(
                """
                SELECT au.id AS app_user_id,
                       cm.id AS mailbox_id,
                       mt.id AS thread_id,
                       mt.gmail_thread_id,
                       mm.id AS message_id,
                       mm.gmail_message_id,
                       ma.id AS attachment_id,
                       ma.gmail_attachment_id,
                       ma.filename,
                       ma.mime_type,
                       ma.byte_size,
                       ma.fetched_at,
                       ma.expires_at,
                       ma.content_nonce,
                       ma.content_ciphertext,
                       mm.body_expires_at AS source_body_expires_at
                FROM app_user au
                JOIN connected_mailbox cm ON cm.app_user_id = au.id
                JOIN mail_thread mt ON mt.mailbox_id = cm.id
                JOIN mail_message mm ON mm.mailbox_id = cm.id AND mm.thread_id = mt.id
                JOIN mail_attachment ma ON ma.message_id = mm.id
                WHERE au.id = ?
                  AND cm.app_user_id = au.id
                  AND mt.id = ?
                  AND mm.id = ?
                  AND ma.id = ?
                """,
                (resultSet, rowNumber) -> mapRecord(resultSet),
                appUserId,
                threadId,
                messageId,
                attachmentId).stream().findFirst();
    }

    int currentCacheTtlDays(UUID appUserId) {
        Integer days = jdbcTemplate.queryForObject(
                "SELECT body_cache_ttl_days FROM app_user WHERE id = ?",
                Integer.class,
                appUserId);
        if (days == null || days < 1 || days > 365) {
            throw new IllegalStateException("The configured message cache lifetime is invalid.");
        }
        return days;
    }

    int clearExpiredContent(AttachmentAccessRecord record, Instant now) {
        return jdbcTemplate.update(
                """
                UPDATE mail_attachment ma
                SET fetched_at = NULL,
                    expires_at = NULL,
                    content_nonce = NULL,
                    content_ciphertext = NULL
                FROM mail_message mm
                JOIN mail_thread mt ON mt.id = mm.thread_id AND mt.mailbox_id = mm.mailbox_id
                JOIN connected_mailbox cm ON cm.id = mt.mailbox_id
                WHERE ma.id = ?
                  AND ma.message_id = mm.id
                  AND mm.id = ?
                  AND mt.id = ?
                  AND cm.id = ?
                  AND cm.app_user_id = ?
                  AND (
                       ma.expires_at <= ?
                       OR (
                            mm.body_expires_at IS NOT NULL
                            AND mm.body_expires_at <= ?
                            AND (ma.fetched_at IS NULL OR ma.fetched_at <= mm.body_expires_at)
                       )
                  )
                """,
                record.attachmentId(),
                record.messageId(),
                record.threadId(),
                record.mailboxId(),
                record.appUserId(),
                Timestamp.from(now),
                Timestamp.from(now));
    }

    void cacheContent(
            AttachmentAccessRecord record,
            long byteSize,
            Instant fetchedAt,
            Instant expiresAt,
            byte[] nonce,
            byte[] ciphertext) {
        int updated = jdbcTemplate.update(
                """
                UPDATE mail_attachment ma
                SET byte_size = ?,
                    fetched_at = ?,
                    expires_at = ?,
                    content_nonce = ?,
                    content_ciphertext = ?
                FROM mail_message mm
                JOIN mail_thread mt ON mt.id = mm.thread_id AND mt.mailbox_id = mm.mailbox_id
                JOIN connected_mailbox cm ON cm.id = mt.mailbox_id
                WHERE ma.id = ?
                  AND ma.message_id = mm.id
                  AND mm.id = ?
                  AND mt.id = ?
                  AND cm.id = ?
                  AND cm.app_user_id = ?
                """,
                byteSize,
                Timestamp.from(fetchedAt),
                Timestamp.from(expiresAt),
                nonce,
                ciphertext,
                record.attachmentId(),
                record.messageId(),
                record.threadId(),
                record.mailboxId(),
                record.appUserId());
        if (updated != 1) {
            throw new AttachmentContentException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "ATTACHMENT_NOT_FOUND",
                    "The requested attachment is no longer available.");
        }
    }

    private static AttachmentAccessRecord mapRecord(ResultSet resultSet) throws SQLException {
        byte[] nonce = resultSet.getBytes("content_nonce");
        byte[] ciphertext = resultSet.getBytes("content_ciphertext");
        Timestamp expiresAt = resultSet.getTimestamp("expires_at");
        Timestamp fetchedAt = resultSet.getTimestamp("fetched_at");
        Timestamp sourceBodyExpiresAt = resultSet.getTimestamp("source_body_expires_at");
        try {
            return new AttachmentAccessRecord(
                    resultSet.getObject("app_user_id", UUID.class),
                    resultSet.getObject("mailbox_id", UUID.class),
                    resultSet.getObject("thread_id", UUID.class),
                    resultSet.getString("gmail_thread_id"),
                    resultSet.getObject("message_id", UUID.class),
                    resultSet.getString("gmail_message_id"),
                    resultSet.getObject("attachment_id", UUID.class),
                    resultSet.getString("gmail_attachment_id"),
                    resultSet.getString("filename"),
                    resultSet.getString("mime_type"),
                    nullableLong(resultSet, "byte_size"),
                    fetchedAt == null ? null : fetchedAt.toInstant(),
                    expiresAt == null ? null : expiresAt.toInstant(),
                    sourceBodyExpiresAt == null ? null : sourceBodyExpiresAt.toInstant(),
                    nonce,
                    ciphertext);
        } finally {
            wipe(nonce);
            wipe(ciphertext);
        }
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static void wipe(byte[] value) {
        if (value != null) Arrays.fill(value, (byte) 0);
    }

    record AttachmentAccessRecord(
            UUID appUserId,
            UUID mailboxId,
            UUID threadId,
            String gmailThreadId,
            UUID messageId,
            String gmailMessageId,
            UUID attachmentId,
            String gmailAttachmentId,
            String filename,
            String mimeType,
            Long byteSize,
            Instant fetchedAt,
            Instant expiresAt,
            Instant sourceBodyExpiresAt,
            byte[] contentNonce,
            byte[] contentCiphertext) {

        AttachmentAccessRecord {
            contentNonce = contentNonce == null ? null : Arrays.copyOf(contentNonce, contentNonce.length);
            contentCiphertext = contentCiphertext == null ? null : Arrays.copyOf(contentCiphertext, contentCiphertext.length);
        }

        @Override
        public byte[] contentNonce() {
            return contentNonce == null ? null : Arrays.copyOf(contentNonce, contentNonce.length);
        }

        @Override
        public byte[] contentCiphertext() {
            return contentCiphertext == null ? null : Arrays.copyOf(contentCiphertext, contentCiphertext.length);
        }

        boolean hasCachedContent() {
            return contentNonce != null && contentCiphertext != null && expiresAt != null;
        }

        boolean cacheIsWithinSourceLifetime(Instant now) {
            if (sourceBodyExpiresAt == null || sourceBodyExpiresAt.isAfter(now)) return true;
            return fetchedAt != null && fetchedAt.isAfter(sourceBodyExpiresAt);
        }

        void destroy() {
            wipe(contentNonce);
            wipe(contentCiphertext);
        }

        @Override
        public String toString() {
            return "AttachmentAccessRecord[owner=REDACTED, gmailIds=REDACTED, filename=REDACTED, "
                    + "contentNonce=REDACTED, contentCiphertext=REDACTED]";
        }
    }
}

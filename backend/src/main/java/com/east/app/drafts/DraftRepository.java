package com.east.app.drafts;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class DraftRepository {

    private static final String DRAFT_COLUMNS = """
            id, app_user_id, mailbox_id, source_thread_id, draft_kind, draft_state, gmail_draft_id,
            payload_nonce, payload_ciphertext, created_at, updated_at, saved_at,
            side_effect_operation, side_effect_started_at
            """;

    private final JdbcTemplate jdbcTemplate;

    DraftRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    boolean mailboxIsOwned(UUID appUserId, UUID mailboxId) {
        Boolean owned = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM connected_mailbox WHERE app_user_id = ? AND id = ?)",
                Boolean.class,
                appUserId,
                mailboxId);
        return Boolean.TRUE.equals(owned);
    }

    Optional<String> ownedMailboxEmail(UUID appUserId, UUID mailboxId) {
        List<String> rows = jdbcTemplate.query(
                "SELECT email FROM connected_mailbox WHERE app_user_id = ? AND id = ?",
                (resultSet, rowNumber) -> resultSet.getString("email"),
                appUserId,
                mailboxId);
        return rows.stream().findFirst();
    }

    Optional<String> ownedGmailThreadId(UUID appUserId, UUID mailboxId, UUID sourceThreadId) {
        List<String> rows = jdbcTemplate.query(
                """
                SELECT mt.gmail_thread_id
                FROM mail_thread mt
                JOIN connected_mailbox cm ON cm.id = mt.mailbox_id
                WHERE cm.app_user_id = ? AND cm.id = ? AND mt.id = ?
                """,
                (resultSet, rowNumber) -> resultSet.getString("gmail_thread_id"),
                appUserId,
                mailboxId,
                sourceThreadId);
        return rows.stream().findFirst();
    }

    List<SourceAttachmentRow> findOwnedSourceAttachments(
            UUID appUserId,
            UUID mailboxId,
            UUID sourceThreadId,
            UUID sourceMessageId) {
        return jdbcTemplate.query(
                """
                SELECT ma.gmail_attachment_id, ma.filename, ma.mime_type, ma.byte_size
                FROM mail_attachment ma
                JOIN mail_message mm ON mm.id = ma.message_id
                JOIN mail_thread mt ON mt.id = mm.thread_id AND mt.mailbox_id = mm.mailbox_id
                JOIN connected_mailbox cm ON cm.id = mt.mailbox_id
                WHERE cm.app_user_id = ? AND cm.id = ? AND mt.id = ? AND mm.id = ?
                ORDER BY ma.filename, ma.id
                """,
                (resultSet, rowNumber) -> new SourceAttachmentRow(
                        resultSet.getString("gmail_attachment_id"),
                        resultSet.getString("filename"),
                        resultSet.getString("mime_type"),
                        resultSet.getObject("byte_size", Long.class)),
                appUserId,
                mailboxId,
                sourceThreadId,
                sourceMessageId);
    }

    List<DraftListItem> listOwned(UUID appUserId) {
        return jdbcTemplate.query(
                """
                SELECT d.id, d.mailbox_id, d.draft_kind, d.draft_state, d.source_thread_id,
                    COUNT(a.id) AS attachment_count, d.created_at, d.updated_at, d.saved_at,
                    d.side_effect_operation, d.side_effect_started_at
                FROM mail_draft d
                LEFT JOIN mail_draft_attachment a ON a.draft_id = d.id
                WHERE d.app_user_id = ?
                GROUP BY d.id
                ORDER BY d.updated_at DESC, d.id
                """,
                (resultSet, rowNumber) -> new DraftListItem(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("mailbox_id", UUID.class),
                        resultSet.getString("draft_kind"),
                        resultSet.getString("draft_state"),
                        resultSet.getObject("source_thread_id", UUID.class),
                        resultSet.getInt("attachment_count"),
                        instant(resultSet, "created_at"),
                        instant(resultSet, "updated_at"),
                        instant(resultSet, "saved_at"),
                        resultSet.getString("side_effect_operation"),
                        instant(resultSet, "side_effect_started_at")),
                appUserId);
    }

    Optional<DraftRow> findOwned(UUID appUserId, UUID draftId, boolean lockForUpdate) {
        String sql = "SELECT " + DRAFT_COLUMNS + " FROM mail_draft WHERE app_user_id = ? AND id = ?"
                + (lockForUpdate ? " FOR UPDATE" : "");
        List<DraftRow> rows = jdbcTemplate.query(sql, this::mapDraft, appUserId, draftId);
        return rows.stream().findFirst();
    }

    int insertLocal(
            UUID draftId,
            UUID appUserId,
            UUID mailboxId,
            UUID sourceThreadId,
            DraftKind kind,
            byte[] nonce,
            byte[] ciphertext) {
        return jdbcTemplate.update(
                """
                INSERT INTO mail_draft (
                    id, app_user_id, mailbox_id, source_thread_id,
                    draft_kind, draft_state, payload_nonce, payload_ciphertext
                ) VALUES (?, ?, ?, ?, ?, 'LOCAL', ?, ?)
                """,
                draftId,
                appUserId,
                mailboxId,
                sourceThreadId,
                kind.name(),
                nonce,
                ciphertext);
    }

    int updateLocal(
            UUID appUserId,
            UUID draftId,
            UUID mailboxId,
            byte[] nonce,
            byte[] ciphertext) {
        return jdbcTemplate.update(
                """
                UPDATE mail_draft
                SET mailbox_id = ?, payload_nonce = ?, payload_ciphertext = ?,
                    updated_at = GREATEST(clock_timestamp(), updated_at + INTERVAL '1 microsecond')
                WHERE app_user_id = ? AND id = ? AND draft_state = 'LOCAL'
                """,
                mailboxId,
                nonce,
                ciphertext,
                appUserId,
                draftId);
    }

    int insertAttachment(
            UUID attachmentId,
            UUID draftId,
            String filename,
            String mimeType,
            long byteSize,
            byte[] nonce,
            byte[] ciphertext) {
        return jdbcTemplate.update(
                """
                INSERT INTO mail_draft_attachment (
                    id, draft_id, filename, mime_type, byte_size, content_nonce, content_ciphertext
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                attachmentId,
                draftId,
                filename,
                mimeType,
                byteSize,
                nonce,
                ciphertext);
    }

    void touchLocalDraft(UUID appUserId, UUID draftId) {
        jdbcTemplate.update(
                """
                UPDATE mail_draft
                SET updated_at = GREATEST(clock_timestamp(), updated_at + INTERVAL '1 microsecond')
                WHERE app_user_id = ? AND id = ? AND draft_state = 'LOCAL'
                """,
                appUserId,
                draftId);
    }

    AttachmentUsage attachmentUsage(UUID appUserId, UUID draftId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) AS attachment_count, COALESCE(SUM(a.byte_size), 0) AS total_bytes
                FROM mail_draft_attachment a
                JOIN mail_draft d ON d.id = a.draft_id
                WHERE d.app_user_id = ? AND d.id = ?
                  AND (d.draft_state = 'LOCAL'
                    OR (d.draft_state IN ('SAVE_PENDING', 'SEND_PENDING')
                        AND d.payload_ciphertext IS NOT NULL))
                """,
                (resultSet, rowNumber) -> new AttachmentUsage(
                        resultSet.getInt("attachment_count"),
                        resultSet.getLong("total_bytes")),
                appUserId,
                draftId);
    }

    List<AttachmentRow> listAttachments(UUID appUserId, UUID draftId) {
        return jdbcTemplate.query(
                """
                SELECT a.id, a.draft_id, a.filename, a.mime_type, a.byte_size,
                    a.content_nonce, a.content_ciphertext
                FROM mail_draft_attachment a
                JOIN mail_draft d ON d.id = a.draft_id
                WHERE d.app_user_id = ? AND d.id = ?
                  AND (d.draft_state = 'LOCAL'
                    OR (d.draft_state IN ('SAVE_PENDING', 'SEND_PENDING')
                        AND d.payload_ciphertext IS NOT NULL))
                ORDER BY a.created_at, a.id
                """,
                this::mapAttachment,
                appUserId,
                draftId);
    }

    List<DraftAttachmentSummary> listAttachmentSummaries(UUID appUserId, UUID draftId) {
        return jdbcTemplate.query(
                """
                SELECT a.id, a.filename, a.mime_type, a.byte_size
                FROM mail_draft_attachment a
                JOIN mail_draft d ON d.id = a.draft_id
                WHERE d.app_user_id = ? AND d.id = ?
                ORDER BY a.created_at, a.id
                """,
                (resultSet, rowNumber) -> new DraftAttachmentSummary(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("filename"),
                        resultSet.getString("mime_type"),
                        resultSet.getLong("byte_size")),
                appUserId,
                draftId);
    }

    boolean deleteAttachment(UUID appUserId, UUID draftId, UUID attachmentId) {
        return jdbcTemplate.update(
                """
                DELETE FROM mail_draft_attachment a
                USING mail_draft d
                WHERE a.draft_id = d.id AND d.app_user_id = ? AND d.id = ?
                  AND d.draft_state = 'LOCAL' AND a.id = ?
                """,
                appUserId,
                draftId,
                attachmentId) == 1;
    }

    int markSavedToGmail(UUID appUserId, UUID draftId, String gmailDraftId) {
        int updated = jdbcTemplate.update(
                """
                UPDATE mail_draft
                SET draft_state = 'SAVED_TO_GMAIL', gmail_draft_id = ?, saved_at = CURRENT_TIMESTAMP,
                    payload_nonce = NULL, payload_ciphertext = NULL,
                    side_effect_operation = NULL, side_effect_started_at = NULL,
                    updated_at = GREATEST(clock_timestamp(), updated_at + INTERVAL '1 microsecond')
                WHERE app_user_id = ? AND id = ? AND draft_state = 'SAVE_PENDING'
                  AND side_effect_operation = 'SAVE_TO_GMAIL'
                """,
                gmailDraftId,
                appUserId,
                draftId);
        if (updated == 1) {
            jdbcTemplate.update("DELETE FROM mail_draft_attachment WHERE draft_id = ?", draftId);
        }
        return updated;
    }

    int markSideEffectPending(UUID appUserId, UUID draftId, String fromState, String operation) {
        String pendingState = switch (operation) {
            case "SAVE_TO_GMAIL" -> "SAVE_PENDING";
            case "SEND" -> "SEND_PENDING";
            default -> throw new IllegalArgumentException("Unsupported draft side effect.");
        };
        return jdbcTemplate.update(
                """
                UPDATE mail_draft
                SET draft_state = ?, side_effect_operation = ?, side_effect_started_at = CURRENT_TIMESTAMP,
                    updated_at = GREATEST(clock_timestamp(), updated_at + INTERVAL '1 microsecond')
                WHERE app_user_id = ? AND id = ? AND draft_state = ?
                  AND side_effect_operation IS NULL
                """,
                pendingState,
                operation,
                appUserId,
                draftId,
                fromState);
    }

    int restorePendingSideEffect(UUID appUserId, UUID draftId, String operation) {
        return jdbcTemplate.update(
                """
                UPDATE mail_draft
                SET draft_state = CASE WHEN gmail_draft_id IS NULL THEN 'LOCAL' ELSE 'SAVED_TO_GMAIL' END,
                    side_effect_operation = NULL, side_effect_started_at = NULL,
                    updated_at = GREATEST(clock_timestamp(), updated_at + INTERVAL '1 microsecond')
                WHERE app_user_id = ? AND id = ?
                  AND draft_state IN ('SAVE_PENDING', 'SEND_PENDING')
                  AND side_effect_operation = ?
                """,
                appUserId,
                draftId,
                operation);
    }

    boolean deletePendingSend(UUID appUserId, UUID draftId) {
        return jdbcTemplate.update(
                """
                DELETE FROM mail_draft
                WHERE app_user_id = ? AND id = ? AND draft_state = 'SEND_PENDING'
                  AND side_effect_operation = 'SEND'
                """,
                appUserId,
                draftId) == 1;
    }

    boolean deleteOwned(UUID appUserId, UUID draftId) {
        return jdbcTemplate.update(
                "DELETE FROM mail_draft WHERE app_user_id = ? AND id = ?",
                appUserId,
                draftId) == 1;
    }

    private DraftRow mapDraft(ResultSet resultSet, int rowNumber) throws SQLException {
        return new DraftRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("app_user_id", UUID.class),
                resultSet.getObject("mailbox_id", UUID.class),
                resultSet.getObject("source_thread_id", UUID.class),
                resultSet.getString("draft_kind"),
                resultSet.getString("draft_state"),
                resultSet.getString("gmail_draft_id"),
                resultSet.getBytes("payload_nonce"),
                resultSet.getBytes("payload_ciphertext"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"),
                instant(resultSet, "saved_at"),
                resultSet.getString("side_effect_operation"),
                instant(resultSet, "side_effect_started_at"));
    }

    private AttachmentRow mapAttachment(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AttachmentRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("draft_id", UUID.class),
                resultSet.getString("filename"),
                resultSet.getString("mime_type"),
                resultSet.getLong("byte_size"),
                resultSet.getBytes("content_nonce"),
                resultSet.getBytes("content_ciphertext"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    record AttachmentUsage(int count, long totalBytes) {
    }

    record SourceAttachmentRow(String gmailAttachmentId, String filename, String mimeType, Long byteSize) {
    }

    record DraftRow(
            UUID id,
            UUID appUserId,
            UUID mailboxId,
            UUID sourceThreadId,
            String kind,
            String state,
            String gmailDraftId,
            byte[] payloadNonce,
            byte[] payloadCiphertext,
            Instant createdAt,
            Instant updatedAt,
            Instant savedAt,
            String sideEffectOperation,
            Instant sideEffectStartedAt) {
        DraftRow {
            payloadNonce = payloadNonce == null ? null : payloadNonce.clone();
            payloadCiphertext = payloadCiphertext == null ? null : payloadCiphertext.clone();
        }

        @Override
        public byte[] payloadNonce() {
            return payloadNonce == null ? null : payloadNonce.clone();
        }

        @Override
        public byte[] payloadCiphertext() {
            return payloadCiphertext == null ? null : payloadCiphertext.clone();
        }

        void destroyPayloadBuffers() {
            wipe(payloadNonce);
            wipe(payloadCiphertext);
        }
    }

    record AttachmentRow(
            UUID id,
            UUID draftId,
            String filename,
            String mimeType,
            long byteSize,
            byte[] contentNonce,
            byte[] contentCiphertext) {
        AttachmentRow {
            contentNonce = contentNonce == null ? null : contentNonce.clone();
            contentCiphertext = contentCiphertext == null ? null : contentCiphertext.clone();
        }

        @Override
        public byte[] contentNonce() {
            return contentNonce == null ? null : contentNonce.clone();
        }

        @Override
        public byte[] contentCiphertext() {
            return contentCiphertext == null ? null : contentCiphertext.clone();
        }

        void destroyPayloadBuffers() {
            wipe(contentNonce);
            wipe(contentCiphertext);
        }
    }

    private static void wipe(byte[] value) {
        if (value != null) {
            java.util.Arrays.fill(value, (byte) 0);
        }
    }
}

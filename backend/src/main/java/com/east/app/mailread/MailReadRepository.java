package com.east.app.mailread;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Ownership-scoped read model and metadata cache for Gmail conversations. */
@Repository
public class MailReadRepository {

    private final JdbcTemplate jdbcTemplate;

    public MailReadRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<MailboxRow> findOwnedMailboxes(UUID appUserId) {
        return jdbcTemplate.query(
                """
                SELECT id, email, display_name, account_color_index
                FROM connected_mailbox
                WHERE app_user_id = ?
                ORDER BY created_at, id
                """,
                this::mapMailbox,
                appUserId);
    }

    public Optional<MailboxRow> findOwnedMailbox(UUID appUserId, UUID mailboxId) {
        return jdbcTemplate.query(
                """
                SELECT id, email, display_name, account_color_index
                FROM connected_mailbox
                WHERE app_user_id = ? AND id = ?
                """,
                this::mapMailbox,
                appUserId,
                mailboxId).stream().findFirst();
    }

    public long countThreads(UUID appUserId, ListFilter filter) {
        QueryPlan plan = buildPlan(appUserId, filter);
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mail_thread mt JOIN connected_mailbox cm ON cm.id = mt.mailbox_id "
                        + plan.whereSql(),
                Long.class,
                plan.arguments().toArray());
        return count == null ? 0 : count;
    }

    public List<ThreadRow> findThreadPage(UUID appUserId, ListFilter filter, int limit, long offset) {
        QueryPlan plan = buildPlan(appUserId, filter);
        List<Object> args = new ArrayList<>(plan.arguments());
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(
                """
                SELECT mt.id, mt.mailbox_id, mt.gmail_thread_id, mt.subject, mt.last_message_at,
                       mt.message_count, mt.is_unread, mt.is_starred,
                       mt.snippet_nonce, mt.snippet_ciphertext, mt.snippet_expires_at,
                       cm.email AS mailbox_email, cm.display_name AS mailbox_display_name,
                       cm.account_color_index,
                       COALESCE(NULLIF(latest.sender_name, ''), latest.sender_email, '') AS latest_sender_name,
                       latest.sender_email AS latest_sender_email,
                       CASE WHEN EXISTS (SELECT 1 FROM mail_message attachment_message
                                         JOIN mail_attachment attachment ON attachment.message_id = attachment_message.id
                                         WHERE attachment_message.thread_id = mt.id)
                            THEN TRUE ELSE NULL END AS attachments_available
                FROM mail_thread mt
                JOIN connected_mailbox cm ON cm.id = mt.mailbox_id
                LEFT JOIN LATERAL (
                    SELECT sender_name, sender_email
                    FROM mail_message
                    WHERE thread_id = mt.id
                    ORDER BY sent_at DESC, id DESC
                    LIMIT 1
                ) latest ON TRUE
                """ + plan.whereSql() + " ORDER BY mt.last_message_at DESC NULLS LAST, mt.mailbox_id, mt.id LIMIT ? OFFSET ?",
                this::mapThread,
                args.toArray());
    }

    public Optional<ThreadRow> findOwnedThreadSummaryByGmailId(
            UUID appUserId, UUID mailboxId, String gmailThreadId) {
        List<ThreadRow> rows = jdbcTemplate.query(
                """
                SELECT mt.id, mt.mailbox_id, mt.gmail_thread_id, mt.subject, mt.last_message_at,
                       mt.message_count, mt.is_unread, mt.is_starred,
                       mt.snippet_nonce, mt.snippet_ciphertext, mt.snippet_expires_at,
                       cm.email AS mailbox_email, cm.display_name AS mailbox_display_name,
                       cm.account_color_index,
                       COALESCE(NULLIF(latest.sender_name, ''), latest.sender_email, '') AS latest_sender_name,
                       latest.sender_email AS latest_sender_email,
                       CASE WHEN EXISTS (SELECT 1 FROM mail_message attachment_message
                                         JOIN mail_attachment attachment ON attachment.message_id = attachment_message.id
                                         WHERE attachment_message.thread_id = mt.id)
                            THEN TRUE ELSE NULL END AS attachments_available
                FROM mail_thread mt
                JOIN connected_mailbox cm ON cm.id = mt.mailbox_id
                LEFT JOIN LATERAL (
                    SELECT sender_name, sender_email
                    FROM mail_message
                    WHERE thread_id = mt.id
                    ORDER BY sent_at DESC, id DESC
                    LIMIT 1
                ) latest ON TRUE
                WHERE cm.app_user_id = ? AND cm.id = ? AND mt.gmail_thread_id = ?
                """,
                this::mapThread,
                appUserId,
                mailboxId,
                gmailThreadId);
        return rows.stream().findFirst();
    }

    public Optional<ThreadOwner> findOwnedThread(UUID appUserId, UUID threadId) {
        return jdbcTemplate.query(
                """
                SELECT mt.id, mt.mailbox_id, mt.gmail_thread_id, mt.subject,
                       cm.email AS mailbox_email, cm.display_name AS mailbox_display_name,
                       cm.account_color_index
                FROM mail_thread mt
                JOIN connected_mailbox cm ON cm.id = mt.mailbox_id
                WHERE mt.id = ? AND cm.app_user_id = ?
                """,
                this::mapThreadOwner,
                threadId,
                appUserId).stream().findFirst();
    }

    public List<MessageRow> findMessages(UUID threadId) {
        return jdbcTemplate.query(
                """
                SELECT id, gmail_message_id, sender_email, sender_name, sent_at,
                       body_mime_type, body_nonce, body_ciphertext, body_expires_at
                FROM mail_message
                WHERE thread_id = ?
                ORDER BY sent_at ASC, id ASC
                """,
                this::mapMessage,
                threadId);
    }

    public List<LabelRow> findLabelsForThread(UUID threadId) {
        return labelsForThreads(List.of(threadId)).getOrDefault(threadId, List.of());
    }

    public Map<UUID, List<LabelRow>> labelsForThreads(List<UUID> threadIds) {
        if (threadIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(threadIds.size(), "?"));
        Map<UUID, LinkedHashMap<UUID, LabelRow>> grouped = new LinkedHashMap<>();
        jdbcTemplate.query(
                """
                SELECT mm.thread_id, ml.id, ml.gmail_label_id, ml.name
                FROM mail_message mm
                JOIN mail_message_label mml ON mml.message_id = mm.id AND mml.mailbox_id = mm.mailbox_id
                JOIN mail_label ml ON ml.id = mml.label_id AND ml.mailbox_id = mm.mailbox_id
                WHERE mm.thread_id IN (%s)
                ORDER BY mm.sent_at DESC, ml.name
                """.formatted(placeholders),
                resultSet -> {
                    UUID threadId = resultSet.getObject("thread_id", UUID.class);
                    LabelRow row = new LabelRow(
                            resultSet.getObject("id", UUID.class),
                            resultSet.getString("gmail_label_id"),
                            resultSet.getString("name"));
                    grouped.computeIfAbsent(threadId, ignored -> new LinkedHashMap<>()).putIfAbsent(row.id(), row);
                },
                threadIds.toArray());
        Map<UUID, List<LabelRow>> result = new HashMap<>();
        grouped.forEach((threadId, labels) -> result.put(threadId, List.copyOf(labels.values())));
        return result;
    }

    public List<LabelRow> findLabelsForMailbox(UUID mailboxId) {
        return jdbcTemplate.query(
                "SELECT id, gmail_label_id, name FROM mail_label WHERE mailbox_id = ? ORDER BY label_type, name",
                this::mapLabel,
                mailboxId);
    }

    public List<MailboxLabelRow> findMailboxLabelCatalog(UUID mailboxId) {
        return jdbcTemplate.query(
                "SELECT id, name, label_type FROM mail_label WHERE mailbox_id = ? ORDER BY label_type, name",
                (resultSet, rowNumber) -> new MailboxLabelRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("name"),
                        resultSet.getString("label_type")),
                mailboxId);
    }

    public Optional<LabelRow> findOwnedLabel(UUID appUserId, UUID mailboxId, UUID labelId) {
        return jdbcTemplate.query(
                """
                SELECT ml.id, ml.gmail_label_id, ml.name
                FROM mail_label ml
                JOIN connected_mailbox cm ON cm.id = ml.mailbox_id
                WHERE cm.app_user_id = ? AND cm.id = ? AND ml.id = ?
                """,
                this::mapLabel,
                appUserId,
                mailboxId,
                labelId).stream().findFirst();
    }

    @Transactional
    public void replaceMailboxLabels(UUID mailboxId, List<GmailLabelMetadata> labels) {
        upsertLabels(mailboxId, labels);
        if (labels.isEmpty()) {
            jdbcTemplate.update("DELETE FROM mail_label WHERE mailbox_id = ?", mailboxId);
            return;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(labels.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(mailboxId);
        labels.forEach(label -> args.add(label.gmailLabelId()));
        jdbcTemplate.update(
                "DELETE FROM mail_label WHERE mailbox_id = ? AND gmail_label_id NOT IN (" + placeholders + ")",
                args.toArray());
    }

    /** Saves a metadata-only Gmail search result; body cache columns are not changed or populated. */
    @Transactional
    public UUID upsertSearchThreadMetadata(UUID mailboxId, SearchThreadMetadata thread) {
        UUID localThreadId = jdbcTemplate.queryForObject(
                """
                INSERT INTO mail_thread (
                    id, mailbox_id, gmail_thread_id, subject, last_message_at,
                    message_count, is_unread, is_starred
                ) VALUES (?, ?, ?, ?, ?, 0, FALSE, FALSE)
                ON CONFLICT (mailbox_id, gmail_thread_id) DO UPDATE SET
                    subject = CASE
                        WHEN mail_thread.last_message_at IS NULL
                             OR EXCLUDED.last_message_at >= mail_thread.last_message_at
                        THEN EXCLUDED.subject ELSE mail_thread.subject END,
                    last_message_at = GREATEST(
                        COALESCE(mail_thread.last_message_at, EXCLUDED.last_message_at),
                        EXCLUDED.last_message_at),
                    updated_at = CURRENT_TIMESTAMP
                RETURNING id
                """,
                UUID.class,
                UUID.randomUUID(),
                mailboxId,
                thread.gmailThreadId(),
                thread.subject(),
                Timestamp.from(thread.latestMessageAt()));

        for (SearchMessageMetadata message : thread.messages()) {
            UUID messageId = jdbcTemplate.queryForObject(
                    """
                    INSERT INTO mail_message (
                        id, mailbox_id, thread_id, gmail_message_id, sender_email, sender_name, sent_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (mailbox_id, gmail_message_id) DO UPDATE SET
                        thread_id = EXCLUDED.thread_id,
                        sender_email = EXCLUDED.sender_email,
                        sender_name = EXCLUDED.sender_name,
                        sent_at = EXCLUDED.sent_at
                    RETURNING id
                    """,
                    UUID.class,
                    UUID.randomUUID(),
                    mailboxId,
                    localThreadId,
                    message.gmailMessageId(),
                    message.senderEmail() == null ? "" : message.senderEmail(),
                    message.senderName(),
                    Timestamp.from(message.sentAt()));
            replaceMessageLabels(mailboxId, messageId, message.gmailLabelIds());
        }

        String placeholders = String.join(",", java.util.Collections.nCopies(thread.messages().size(), "?"));
        List<Object> staleArguments = new ArrayList<>();
        staleArguments.add(mailboxId);
        staleArguments.add(localThreadId);
        thread.messages().forEach(message -> staleArguments.add(message.gmailMessageId()));
        jdbcTemplate.update(
                "DELETE FROM mail_message WHERE mailbox_id = ? AND thread_id = ? AND gmail_message_id NOT IN ("
                        + placeholders + ")",
                staleArguments.toArray());

        refreshThreadMetadata(localThreadId, mailboxId);
        return localThreadId;
    }

    public record ListFilter(
            UUID mailboxId,
            String folder,
            String category,
            Boolean unread,
            Boolean starred,
            UUID labelId) { }

    public record MailboxRow(UUID id, String email, String displayName, int colorIndex) {
        MailReadModels.MailboxIdentity identity() {
            return new MailReadModels.MailboxIdentity(
                    id, email, displayName == null || displayName.isBlank() ? email : displayName, null, colorIndex);
        }
    }

    public record ThreadRow(
            UUID id,
            UUID mailboxId,
            String gmailThreadId,
            String subject,
            Instant latestMessageAt,
            int messageCount,
            boolean unread,
            boolean starred,
            byte[] snippetNonce,
            byte[] snippetCiphertext,
            Instant snippetExpiresAt,
            MailboxRow mailbox,
            String senderName,
            String senderEmail,
            Boolean attachmentsAvailable) { }

    public record ThreadOwner(
            UUID id,
            UUID mailboxId,
            String gmailThreadId,
            String subject,
            MailboxRow mailbox) { }

    public record MessageRow(
            UUID id,
            String gmailMessageId,
            String senderEmail,
            String senderName,
            Instant sentAt,
            String bodyMimeType,
            byte[] bodyNonce,
            byte[] bodyCiphertext,
            Instant bodyExpiresAt) { }

    public record AttachmentRow(UUID id, String filename, String mimeType, Long byteSize) { }

    public record LabelRow(UUID id, String gmailLabelId, String name) { }

    public record MailboxLabelRow(UUID id, String name, String type) { }

    public record GmailLabelMetadata(String gmailLabelId, String name, String type) { }

    public record SearchThreadMetadata(
            String gmailThreadId,
            String subject,
            Instant latestMessageAt,
            List<SearchMessageMetadata> messages) {
        public SearchThreadMetadata {
            messages = List.copyOf(messages);
        }
    }

    public record SearchMessageMetadata(
            String gmailMessageId,
            String senderEmail,
            String senderName,
            Instant sentAt,
            List<String> gmailLabelIds) {
        public SearchMessageMetadata {
            gmailLabelIds = List.copyOf(gmailLabelIds);
        }
    }

    public List<AttachmentRow> findAttachments(UUID messageId) {
        return jdbcTemplate.query(
                "SELECT id, filename, mime_type, byte_size FROM mail_attachment WHERE message_id = ? ORDER BY filename",
                (resultSet, rowNumber) -> new AttachmentRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("filename"),
                        resultSet.getString("mime_type"),
                        getNullableLong(resultSet, "byte_size")),
                messageId);
    }

    private QueryPlan buildPlan(UUID appUserId, ListFilter filter) {
        StringBuilder where = new StringBuilder(" WHERE cm.app_user_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(appUserId);
        if (filter.mailboxId() != null) {
            where.append(" AND cm.id = ?");
            args.add(filter.mailboxId());
        }
        String folderLabel = switch (filter.folder() == null ? "" : filter.folder()) {
            case "INBOX", "SENT", "DRAFTS", "SPAM", "TRASH", "SNOOZED" -> filter.folder();
            default -> null;
        };
        if (folderLabel != null) {
            where.append(" AND ").append(labelExists());
            args.add(folderLabel);
        } else if ("STARRED".equals(filter.folder())) {
            where.append(" AND mt.is_starred = TRUE");
        } else if ("ALL_MAIL".equals(filter.folder())) {
            where.append(" AND NOT ").append(labelExists());
            args.add("SPAM");
            where.append(" AND NOT ").append(labelExists());
            args.add("TRASH");
        }
        if (filter.category() != null) {
            where.append(" AND ").append(labelExists());
            args.add(categoryLabelId(filter.category()));
        }
        if (Boolean.TRUE.equals(filter.unread())) where.append(" AND mt.is_unread = TRUE");
        else if (Boolean.FALSE.equals(filter.unread())) where.append(" AND mt.is_unread = FALSE");
        if (Boolean.TRUE.equals(filter.starred())) where.append(" AND mt.is_starred = TRUE");
        else if (Boolean.FALSE.equals(filter.starred())) where.append(" AND mt.is_starred = FALSE");
        if (filter.labelId() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM mail_message mm_label "
                    + "JOIN mail_message_label mml ON mml.message_id = mm_label.id "
                    + "WHERE mm_label.thread_id = mt.id AND mml.label_id = ?)");
            args.add(filter.labelId());
        }

        return new QueryPlan(where.toString(), args);
    }

    private static String labelExists() {
        return "EXISTS (SELECT 1 FROM mail_message mm_folder "
                + "JOIN mail_message_label mml_folder ON mml_folder.message_id = mm_folder.id "
                + "JOIN mail_label ml_folder ON ml_folder.id = mml_folder.label_id "
                + "WHERE mm_folder.thread_id = mt.id AND ml_folder.gmail_label_id = ? )";
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

    private void replaceMessageLabels(UUID mailboxId, UUID messageId, List<String> gmailLabelIds) {
        jdbcTemplate.update("DELETE FROM mail_message_label WHERE mailbox_id = ? AND message_id = ?", mailboxId, messageId);
        for (String gmailLabelId : gmailLabelIds) {
            jdbcTemplate.update(
                    """
                    INSERT INTO mail_message_label (message_id, label_id, mailbox_id)
                    SELECT ?, id, ? FROM mail_label WHERE mailbox_id = ? AND gmail_label_id = ?
                    ON CONFLICT (message_id, label_id) DO NOTHING
                    """,
                    messageId,
                    mailboxId,
                    mailboxId,
                    gmailLabelId);
        }
    }

    private void refreshThreadMetadata(UUID threadId, UUID mailboxId) {
        jdbcTemplate.update(
                """
                UPDATE mail_thread mt
                SET message_count = (SELECT COUNT(*) FROM mail_message mm WHERE mm.thread_id = mt.id),
                    last_message_at = (SELECT MAX(mm.sent_at) FROM mail_message mm WHERE mm.thread_id = mt.id),
                    is_unread = EXISTS (
                        SELECT 1 FROM mail_message mm
                        JOIN mail_message_label mml ON mml.message_id = mm.id
                        JOIN mail_label ml ON ml.id = mml.label_id
                        WHERE mm.thread_id = mt.id AND ml.gmail_label_id = 'UNREAD'),
                    is_starred = EXISTS (
                        SELECT 1 FROM mail_message mm
                        JOIN mail_message_label mml ON mml.message_id = mm.id
                        JOIN mail_label ml ON ml.id = mml.label_id
                        WHERE mm.thread_id = mt.id AND ml.gmail_label_id = 'STARRED'),
                    updated_at = CURRENT_TIMESTAMP
                WHERE mt.id = ? AND mt.mailbox_id = ?
                """,
                threadId,
                mailboxId);
    }

    private static String categoryLabelId(String category) {
        return switch (category) {
            case "PRIMARY" -> "CATEGORY_PERSONAL";
            case "SOCIAL" -> "CATEGORY_SOCIAL";
            case "PROMOTIONS" -> "CATEGORY_PROMOTIONS";
            case "UPDATES" -> "CATEGORY_UPDATES";
            case "FORUMS" -> "CATEGORY_FORUMS";
            default -> throw new IllegalArgumentException("Unsupported Gmail category.");
        };
    }

    private MailboxRow mapMailbox(ResultSet rs, int row) throws SQLException {
        return new MailboxRow(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getInt("account_color_index"));
    }

    private ThreadRow mapThread(ResultSet rs, int row) throws SQLException {
        Timestamp latestMessageAt = rs.getTimestamp("last_message_at");
        Timestamp snippetExpiresAt = rs.getTimestamp("snippet_expires_at");
        return new ThreadRow(
                rs.getObject("id", UUID.class),
                rs.getObject("mailbox_id", UUID.class),
                rs.getString("gmail_thread_id"),
                rs.getString("subject"),
                latestMessageAt == null ? null : latestMessageAt.toInstant(),
                rs.getInt("message_count"),
                rs.getBoolean("is_unread"),
                rs.getBoolean("is_starred"),
                rs.getBytes("snippet_nonce"),
                rs.getBytes("snippet_ciphertext"),
                snippetExpiresAt == null ? null : snippetExpiresAt.toInstant(),
                new MailboxRow(
                        rs.getObject("mailbox_id", UUID.class),
                        rs.getString("mailbox_email"),
                        rs.getString("mailbox_display_name"),
                        rs.getInt("account_color_index")),
                rs.getString("latest_sender_name"),
                rs.getString("latest_sender_email"),
                rs.getObject("attachments_available", Boolean.class));
    }

    private ThreadOwner mapThreadOwner(ResultSet rs, int row) throws SQLException {
        UUID mailboxId = rs.getObject("mailbox_id", UUID.class);
        return new ThreadOwner(
                rs.getObject("id", UUID.class),
                mailboxId,
                rs.getString("gmail_thread_id"),
                rs.getString("subject"),
                new MailboxRow(mailboxId, rs.getString("mailbox_email"),
                        rs.getString("mailbox_display_name"), rs.getInt("account_color_index")));
    }

    private MessageRow mapMessage(ResultSet rs, int row) throws SQLException {
        Timestamp sentAt = rs.getTimestamp("sent_at");
        Timestamp expiresAt = rs.getTimestamp("body_expires_at");
        return new MessageRow(
                rs.getObject("id", UUID.class),
                rs.getString("gmail_message_id"),
                rs.getString("sender_email"),
                rs.getString("sender_name"),
                sentAt == null ? null : sentAt.toInstant(),
                rs.getString("body_mime_type"),
                rs.getBytes("body_nonce"),
                rs.getBytes("body_ciphertext"),
                expiresAt == null ? null : expiresAt.toInstant());
    }

    private LabelRow mapLabel(ResultSet rs, int row) throws SQLException {
        return new LabelRow(
                rs.getObject("id", UUID.class),
                rs.getString("gmail_label_id"),
                rs.getString("name"));
    }

    private static Long getNullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private record QueryPlan(String whereSql, List<Object> arguments) { }
}

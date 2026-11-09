package com.east.app.mailactions;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MailActionsRepository {

    private final JdbcTemplate jdbcTemplate;

    public MailActionsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    OwnedThread findOwnedThread(UUID appUserId, UUID threadId) {
        List<OwnedThread> rows = jdbcTemplate.query(
                """
                SELECT mt.id, mt.mailbox_id, mt.gmail_thread_id
                FROM mail_thread mt
                JOIN connected_mailbox cm ON cm.id = mt.mailbox_id
                WHERE mt.id = ? AND cm.app_user_id = ?
                """,
                OWNED_THREAD_MAPPER,
                threadId,
                appUserId);
        return rows.stream().findFirst().orElse(null);
    }

    boolean mailboxIsOwned(UUID appUserId, UUID mailboxId) {
        return !jdbcTemplate.query(
                "SELECT 1 FROM connected_mailbox WHERE id = ? AND app_user_id = ?",
                (resultSet, rowNumber) -> resultSet.getInt(1),
                mailboxId,
                appUserId).isEmpty();
    }

    UserLabel findOwnedUserLabel(UUID appUserId, UUID mailboxId, UUID labelId) {
        List<UserLabel> rows = jdbcTemplate.query(
                """
                SELECT ml.id, ml.gmail_label_id, ml.name
                FROM mail_label ml
                JOIN connected_mailbox cm ON cm.id = ml.mailbox_id
                WHERE cm.app_user_id = ?
                  AND ml.mailbox_id = ?
                  AND ml.id = ?
                  AND ml.label_type = 'USER'
                """,
                USER_LABEL_MAPPER,
                appUserId,
                mailboxId,
                labelId);
        return rows.stream().findFirst().orElse(null);
    }

    List<UserLabel> findOwnedUserLabels(UUID appUserId, UUID mailboxId, List<UUID> labelIds) {
        if (labelIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(labelIds.size(), "?"));
        List<Object> arguments = new ArrayList<>(labelIds.size() + 2);
        arguments.add(appUserId);
        arguments.add(mailboxId);
        arguments.addAll(labelIds);
        return jdbcTemplate.query(
                """
                SELECT ml.id, ml.gmail_label_id, ml.name
                FROM mail_label ml
                JOIN connected_mailbox cm ON cm.id = ml.mailbox_id
                WHERE cm.app_user_id = ?
                  AND ml.mailbox_id = ?
                  AND ml.label_type = 'USER'
                  AND ml.id IN (%s)
                """.formatted(placeholders),
                USER_LABEL_MAPPER,
                arguments.toArray());
    }

    void insertUserLabel(UUID id, UUID mailboxId, String gmailLabelId, String name) {
        jdbcTemplate.update(
                """
                INSERT INTO mail_label (id, mailbox_id, gmail_label_id, name, label_type)
                VALUES (?, ?, ?, ?, 'USER')
                """,
                id,
                mailboxId,
                gmailLabelId,
                name);
    }

    int renameOwnedUserLabel(UUID appUserId, UUID mailboxId, UUID labelId, String name) {
        return jdbcTemplate.update(
                """
                UPDATE mail_label ml
                SET name = ?
                FROM connected_mailbox cm
                WHERE ml.id = ?
                  AND ml.mailbox_id = ?
                  AND ml.mailbox_id = cm.id
                  AND cm.app_user_id = ?
                  AND ml.label_type = 'USER'
                """,
                name,
                labelId,
                mailboxId,
                appUserId);
    }

    int deleteOwnedUserLabel(UUID appUserId, UUID mailboxId, UUID labelId) {
        return jdbcTemplate.update(
                """
                DELETE FROM mail_label ml
                USING connected_mailbox cm
                WHERE ml.id = ?
                  AND ml.mailbox_id = ?
                  AND ml.mailbox_id = cm.id
                  AND cm.app_user_id = ?
                  AND ml.label_type = 'USER'
                """,
                labelId,
                mailboxId,
                appUserId);
    }

    /** Applies local label links and thread flags in a per-thread transaction after Gmail succeeds. */
    @Transactional
    public void synchronizeThreadState(
            UUID threadId,
            UUID mailboxId,
            List<String> addGmailLabelIds,
            List<String> removeGmailLabelIds,
            Boolean unread,
            Boolean starred) {
        if (!removeGmailLabelIds.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(removeGmailLabelIds.size(), "?"));
            List<Object> arguments = new ArrayList<>(removeGmailLabelIds.size() + 2);
            arguments.add(threadId);
            arguments.add(mailboxId);
            arguments.addAll(removeGmailLabelIds);
            jdbcTemplate.update(
                    """
                    DELETE FROM mail_message_label mml
                    USING mail_message mm, mail_label ml
                    WHERE mml.message_id = mm.id
                      AND mml.label_id = ml.id
                      AND mm.thread_id = ?
                      AND mm.mailbox_id = ?
                      AND ml.mailbox_id = mm.mailbox_id
                      AND ml.gmail_label_id IN (%s)
                    """.formatted(placeholders),
                    arguments.toArray());
        }

        if (!addGmailLabelIds.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(addGmailLabelIds.size(), "?"));
            List<Object> arguments = new ArrayList<>(addGmailLabelIds.size() + 2);
            arguments.add(threadId);
            arguments.add(mailboxId);
            arguments.addAll(addGmailLabelIds);
            jdbcTemplate.update(
                    """
                    INSERT INTO mail_message_label (message_id, label_id, mailbox_id)
                    SELECT mm.id, ml.id, mm.mailbox_id
                    FROM mail_message mm
                    JOIN mail_label ml ON ml.mailbox_id = mm.mailbox_id
                    WHERE mm.thread_id = ?
                      AND mm.mailbox_id = ?
                      AND ml.gmail_label_id IN (%s)
                    ON CONFLICT (message_id, label_id) DO NOTHING
                    """.formatted(placeholders),
                    arguments.toArray());
        }

        jdbcTemplate.update(
                """
                UPDATE mail_thread
                SET is_unread = COALESCE(?, is_unread),
                    is_starred = COALESCE(?, is_starred),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND mailbox_id = ?
                """,
                unread,
                starred,
                threadId,
                mailboxId);
    }

    private static final RowMapper<OwnedThread> OWNED_THREAD_MAPPER = (resultSet, rowNumber) ->
            new OwnedThread(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("mailbox_id", UUID.class),
                    resultSet.getString("gmail_thread_id"));

    private static final RowMapper<UserLabel> USER_LABEL_MAPPER = (resultSet, rowNumber) ->
            new UserLabel(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getString("gmail_label_id"),
                    resultSet.getString("name"));

    record OwnedThread(UUID id, UUID mailboxId, String gmailThreadId) {
    }

    record UserLabel(UUID id, String gmailLabelId, String name) {
    }
}

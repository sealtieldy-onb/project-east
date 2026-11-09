package com.east.app.auth;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.stereotype.Repository;

@Repository
public class MailboxRepository {

    private static final String COLUMNS = """
            id, app_user_id, google_subject, email, display_name, account_color_index,
            reauthorization_required,
            granted_scopes, oauth_refresh_token_nonce, oauth_refresh_token_ciphertext,
            last_sync_at, created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public MailboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void lockAppUser(UUID appUserId) {
        List<UUID> rows = jdbcTemplate.query(
                "SELECT id FROM app_user WHERE id = ? FOR UPDATE",
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                appUserId);
        if (rows.isEmpty()) {
            throw new AuthNotFoundException();
        }
    }

    public Optional<ConnectedMailbox> findByOwnerAndGoogleSubject(UUID appUserId, String googleSubject) {
        List<ConnectedMailbox> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM connected_mailbox WHERE app_user_id = ? AND google_subject = ?",
                this::mapMailbox,
                appUserId,
                googleSubject);
        return rows.stream().findFirst();
    }

    public Optional<ConnectedMailbox> findOwned(UUID appUserId, UUID mailboxId) {
        List<ConnectedMailbox> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM connected_mailbox WHERE app_user_id = ? AND id = ?",
                this::mapMailbox,
                appUserId,
                mailboxId);
        return rows.stream().findFirst();
    }

    public List<MailboxSummary> listOwnedSummaries(UUID appUserId) {
        return jdbcTemplate.query(
                """
                SELECT cm.id, cm.email, cm.display_name, cm.account_color_index,
                       cm.reauthorization_required, cm.last_sync_at,
                       COALESCE(
                           ARRAY_AGG(DISTINCT ml.gmail_label_id) FILTER (
                               WHERE ml.gmail_label_id IN (
                                   'CATEGORY_PERSONAL', 'CATEGORY_SOCIAL', 'CATEGORY_PROMOTIONS',
                                   'CATEGORY_UPDATES', 'CATEGORY_FORUMS'
                               )
                           ),
                           ARRAY[]::TEXT[]
                       ) AS category_ids
                FROM connected_mailbox cm
                LEFT JOIN mail_label ml ON ml.mailbox_id = cm.id
                WHERE cm.app_user_id = ?
                GROUP BY cm.id, cm.email, cm.display_name, cm.account_color_index,
                         cm.reauthorization_required, cm.last_sync_at, cm.created_at
                ORDER BY cm.created_at, cm.id
                """,
                (resultSet, rowNumber) -> {
                    java.sql.Array rawCategories = resultSet.getArray("category_ids");
                    String[] categoryIds = rawCategories == null ? new String[0] : (String[]) rawCategories.getArray();
                    return new MailboxSummary(
                            resultSet.getObject("id", UUID.class),
                            resultSet.getString("email"),
                            resultSet.getString("display_name"),
                            resultSet.getInt("account_color_index"),
                            resultSet.getBoolean("reauthorization_required"),
                            getInstant(resultSet, "last_sync_at"),
                            java.util.Arrays.stream(categoryIds)
                                    .map(MailboxRepository::normalizeCategory)
                                    .distinct()
                                    .sorted()
                                    .toList());
                },
                appUserId);
    }

    public OptionalInt nextAvailableColorIndex(UUID appUserId, String googleSubject) {
        Set<Integer> used = new HashSet<>(jdbcTemplate.query(
                "SELECT account_color_index FROM connected_mailbox WHERE app_user_id = ?",
                (resultSet, rowNumber) -> resultSet.getInt(1),
                appUserId));
        int candidate = (int) Math.floorMod((long) googleSubject.hashCode(), (long) Integer.MAX_VALUE + 1);
        int stableStart = candidate;
        do {
            if (!used.contains(candidate)) {
                return OptionalInt.of(candidate);
            }
            candidate = candidate == Integer.MAX_VALUE ? 0 : candidate + 1;
        } while (candidate != stableStart);
        return OptionalInt.empty();
    }

    public void upsertMailbox(
            UUID mailboxId,
            UUID appUserId,
            String googleSubject,
            String email,
            String displayName,
            int colorIndex,
            List<String> grantedScopes,
            byte[] refreshTokenNonce,
            byte[] refreshTokenCiphertext) {
        String sql = """
                INSERT INTO connected_mailbox (
                    id, app_user_id, google_subject, email, display_name, account_color_index,
                    granted_scopes, oauth_refresh_token_nonce, oauth_refresh_token_ciphertext
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (app_user_id, google_subject) DO UPDATE SET
                    email = EXCLUDED.email,
                    display_name = EXCLUDED.display_name,
                    granted_scopes = EXCLUDED.granted_scopes,
                    reauthorization_required = FALSE,
                    oauth_refresh_token_nonce = COALESCE(
                        EXCLUDED.oauth_refresh_token_nonce,
                        connected_mailbox.oauth_refresh_token_nonce),
                    oauth_refresh_token_ciphertext = COALESCE(
                        EXCLUDED.oauth_refresh_token_ciphertext,
                        connected_mailbox.oauth_refresh_token_ciphertext),
                    updated_at = CURRENT_TIMESTAMP
                """;
        PreparedStatementCreator creator = connection -> {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setObject(1, mailboxId);
            statement.setObject(2, appUserId);
            statement.setString(3, googleSubject);
            statement.setString(4, email);
            statement.setString(5, displayName);
            statement.setInt(6, colorIndex);
            Array scopes = connection.createArrayOf("text", grantedScopes.toArray(String[]::new));
            statement.setArray(7, scopes);
            statement.setBytes(8, refreshTokenNonce);
            statement.setBytes(9, refreshTokenCiphertext);
            return statement;
        };
        jdbcTemplate.update(creator);
    }

    /** Persist only the safe account status; provider error details never enter the database. */
    public void markReauthorizationRequired(UUID appUserId, UUID mailboxId) {
        jdbcTemplate.update(
                """
                UPDATE connected_mailbox
                SET reauthorization_required = TRUE, updated_at = CURRENT_TIMESTAMP
                WHERE app_user_id = ? AND id = ? AND reauthorization_required = FALSE
                """,
                appUserId,
                mailboxId);
    }

    public boolean deleteOwned(UUID appUserId, UUID mailboxId) {
        return jdbcTemplate.update(
                "DELETE FROM connected_mailbox WHERE app_user_id = ? AND id = ?",
                appUserId,
                mailboxId) == 1;
    }

    /** Purge derived reports that used this mailbox before its message/source rows cascade away. */
    public int deleteSummaryRunsForMailbox(UUID appUserId, UUID mailboxId) {
        return jdbcTemplate.update(
                """
                DELETE FROM summary_run sr
                WHERE sr.app_user_id = ?
                  AND EXISTS (
                      SELECT 1 FROM connected_mailbox cm
                      WHERE cm.id = ? AND cm.app_user_id = sr.app_user_id
                  )
                  AND (
                      EXISTS (
                          SELECT 1 FROM summary_run_source srs
                          WHERE srs.summary_run_id = sr.id
                            AND srs.app_user_id = sr.app_user_id
                            AND srs.mailbox_id = ?
                      )
                      OR EXISTS (
                          SELECT 1
                          FROM assistant_insight ai
                          JOIN assistant_insight_source ais ON ais.insight_id = ai.id
                          JOIN mail_message mm ON mm.id = ais.message_id
                          WHERE ai.summary_run_id = sr.id
                            AND mm.mailbox_id = ?
                      )
                  )
                """,
                appUserId,
                mailboxId,
                mailboxId,
                mailboxId);
    }

    private ConnectedMailbox mapMailbox(ResultSet resultSet, int rowNumber) throws SQLException {
        Array scopeArray = resultSet.getArray("granted_scopes");
        String[] scopeValues = scopeArray == null ? new String[0] : (String[]) scopeArray.getArray();
        return new ConnectedMailbox(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("app_user_id", UUID.class),
                resultSet.getString("google_subject"),
                resultSet.getString("email"),
                resultSet.getString("display_name"),
                resultSet.getInt("account_color_index"),
                resultSet.getBoolean("reauthorization_required"),
                List.copyOf(Arrays.asList(scopeValues)),
                resultSet.getBytes("oauth_refresh_token_nonce"),
                resultSet.getBytes("oauth_refresh_token_ciphertext"),
                getInstant(resultSet, "last_sync_at"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private static Instant getInstant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String normalizeCategory(String labelId) {
        return switch (labelId) {
            case "CATEGORY_PERSONAL" -> "PRIMARY";
            case "CATEGORY_SOCIAL" -> "SOCIAL";
            case "CATEGORY_PROMOTIONS" -> "PROMOTIONS";
            case "CATEGORY_UPDATES" -> "UPDATES";
            case "CATEGORY_FORUMS" -> "FORUMS";
            default -> "";
        };
    }
}

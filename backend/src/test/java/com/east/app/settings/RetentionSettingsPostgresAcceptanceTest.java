package com.east.app.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.east.app.EastApplication;
import com.east.app.auth.MailboxRepository;
import com.east.app.retention.RetentionCleanupService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Opt-in PostgreSQL acceptance for changeset 010, body-cache TTL, retention, and mailbox cleanup.
 * Requires a disposable database named with the east_retention_test_ prefix and
 * EAST_RUN_POSTGRES_ACCEPTANCE=true.
 */
class RetentionSettingsPostgresAcceptanceTest {

    private static final UUID USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID MAILBOX_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID SECOND_MAILBOX_ID = UUID.fromString("10000000-0000-4000-8000-000000000008");
    private static final UUID THREAD_ID = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID MESSAGE_ID = UUID.fromString("10000000-0000-4000-8000-000000000004");
    private static final UUID ATTACHMENT_ID = UUID.fromString("10000000-0000-4000-8000-000000000005");
    private static final UUID SUMMARY_ID = UUID.fromString("10000000-0000-4000-8000-000000000006");
    private static final UUID INSIGHT_ID = UUID.fromString("10000000-0000-4000-8000-000000000007");
    private static final UUID SECOND_THREAD_ID = UUID.fromString("10000000-0000-4000-8000-000000000009");
    private static final UUID SECOND_MESSAGE_ID = UUID.fromString("10000000-0000-4000-8000-000000000010");
    private static final UUID COMBINED_SUMMARY_ID = UUID.fromString("10000000-0000-4000-8000-000000000011");
    private static final UUID EMPTY_SCOPE_SUMMARY_ID = UUID.fromString("10000000-0000-4000-8000-000000000012");
    private static final UUID COMBINED_INSIGHT_ID = UUID.fromString("10000000-0000-4000-8000-000000000013");

    @Test
    @EnabledIfEnvironmentVariable(named = "EAST_RUN_POSTGRES_ACCEPTANCE", matches = "true")
    void updatesCacheAndDerivedExpiriesAgainstPostgres() {
        String databaseName = System.getenv("EAST_RETENTION_TEST_DB");
        if (databaseName == null || !databaseName.matches("east_retention_test_[a-z0-9_]+")) {
            throw new IllegalStateException("Set EAST_RETENTION_TEST_DB to an isolated east_retention_test_* database.");
        }
        String port = System.getenv().getOrDefault("EAST_DB_PORT", "15432");
        String databaseUser = System.getenv().getOrDefault("EAST_DB_USER", "east");
        String datasourceUrl = "jdbc:postgresql://127.0.0.1:" + port + "/" + databaseName;

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(EastApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(
                        "--spring.datasource.url=" + datasourceUrl,
                        "--spring.datasource.username=" + databaseUser,
                        "--server.port=0",
                        "--east.gmail.sync-initial-delay-ms=600000",
                        "--east.reports.daily-initial-delay-ms=600000",
                        "--spring.main.banner-mode=off")) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            SettingsService settings = context.getBean(SettingsService.class);
            RetentionCleanupService retentionCleanup = context.getBean(RetentionCleanupService.class);
            MailboxRepository mailboxRepository = new MailboxRepository(jdbc);

            seedCacheRows(jdbc);
            assertEquals(60, settings.getBodyCacheDays());

            assertEquals(30, settings.updateBodyCacheDays(30));
            assertEquals(30, settings.getBodyCacheDays());
            assertExpiryDays(jdbc, "mail_message", "body_cached_at", "body_expires_at", MESSAGE_ID, 30);
            assertSnippetExpiryDays(jdbc, 30);
            assertExpiryDays(jdbc, "mail_attachment", "fetched_at", "expires_at", ATTACHMENT_ID, 30);
            assertSourceExpiryMatches(jdbc);

            assertEquals(2, settings.updateBodyCacheDays(2));
            assertEquals(2, settings.getBodyCacheDays());
            assertExpiryDays(jdbc, "mail_message", "body_cached_at", "body_expires_at", MESSAGE_ID, 2);
            assertSnippetExpiryDays(jdbc, 2);
            assertExpiryDays(jdbc, "mail_attachment", "fetched_at", "expires_at", ATTACHMENT_ID, 2);
            assertSourceExpiryMatches(jdbc);

            jdbc.update("""
                    UPDATE mail_message
                    SET body_cached_at = CURRENT_TIMESTAMP - INTERVAL '4 days',
                        body_expires_at = CURRENT_TIMESTAMP - INTERVAL '2 days'
                    WHERE id = ?
                    """, MESSAGE_ID);
            jdbc.update("UPDATE mail_thread SET snippet_expires_at = CURRENT_TIMESTAMP - INTERVAL '2 days' WHERE id = ?",
                    THREAD_ID);
            retentionCleanup.purgeExpiredData();

            assertEquals(0, count(jdbc, "summary_run", "id", SUMMARY_ID));
            assertEquals(0, count(jdbc, "assistant_insight", "id", INSIGHT_ID));
            assertEquals(0, count(jdbc, "summary_run_source", "summary_run_id", SUMMARY_ID));
            assertEquals(0, count(jdbc, "assistant_insight_source", "insight_id", INSIGHT_ID));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM mail_message WHERE id = ? AND sender_email = 'sender@example.invalid' "
                            + "AND body_ciphertext IS NULL AND body_cached_at IS NULL AND body_expires_at IS NULL",
                    Integer.class,
                    MESSAGE_ID));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM mail_thread WHERE id = ? AND subject = 'synthetic retention' "
                            + "AND snippet_ciphertext IS NULL AND snippet_expires_at IS NULL",
                    Integer.class,
                    THREAD_ID));

            seedDisconnectRows(jdbc);
            assertEquals(2, mailboxRepository.deleteSummaryRunsForMailbox(USER_ID, MAILBOX_ID));
            assertEquals(0, count(jdbc, "summary_run", "id", COMBINED_SUMMARY_ID));
            assertEquals(0, count(jdbc, "summary_run", "id", EMPTY_SCOPE_SUMMARY_ID));
            assertEquals(0, count(jdbc, "assistant_insight", "id", COMBINED_INSIGHT_ID));
            assertEquals(0, count(jdbc, "summary_run_source", "summary_run_id", COMBINED_SUMMARY_ID));
            assertEquals(0, count(jdbc, "assistant_insight_source", "insight_id", COMBINED_INSIGHT_ID));
            assertTrue(mailboxRepository.deleteOwned(USER_ID, MAILBOX_ID));
            assertEquals(0, count(jdbc, "connected_mailbox", "id", MAILBOX_ID));
            assertEquals(1, count(jdbc, "connected_mailbox", "id", SECOND_MAILBOX_ID));
        }
    }

    private static void seedCacheRows(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM app_user WHERE id = ?", USER_ID);
        jdbc.update("INSERT INTO app_user (id, google_subject, email) VALUES (?, ?, ?)",
                USER_ID, "retention-ttl-smoke", "retention-ttl@example.invalid");
        jdbc.update("""
                INSERT INTO connected_mailbox (id, app_user_id, google_subject, email, account_color_index)
                VALUES (?, ?, ?, ?, 1)
                """, SECOND_MAILBOX_ID, USER_ID, "retention-ttl-second-mailbox", "second@example.invalid");
        jdbc.update("""
                INSERT INTO connected_mailbox (id, app_user_id, google_subject, email, account_color_index)
                VALUES (?, ?, ?, ?, 0)
                """, MAILBOX_ID, USER_ID, "retention-ttl-mailbox", "retention-ttl@example.invalid");
        jdbc.update("""
                INSERT INTO mail_thread
                    (id, mailbox_id, gmail_thread_id, subject, last_message_at, message_count,
                     snippet_nonce, snippet_ciphertext, snippet_expires_at)
                VALUES (?, ?, ?, 'synthetic retention', CURRENT_TIMESTAMP, 1,
                        decode(repeat('11', 12), 'hex'), decode(repeat('aa', 32), 'hex'),
                        CURRENT_TIMESTAMP + INTERVAL '6 days')
                """, THREAD_ID, MAILBOX_ID, "retention-ttl-thread");
        jdbc.update("""
                INSERT INTO mail_message
                    (id, mailbox_id, thread_id, gmail_message_id, sender_email, sent_at,
                     body_mime_type, body_nonce, body_ciphertext, body_cached_at, body_expires_at)
                VALUES (?, ?, ?, ?, 'sender@example.invalid', CURRENT_TIMESTAMP,
                        'text/plain', decode(repeat('22', 12), 'hex'), decode(repeat('bb', 32), 'hex'),
                        CURRENT_TIMESTAMP - INTERVAL '1 day', CURRENT_TIMESTAMP + INTERVAL '6 days')
                """, MESSAGE_ID, MAILBOX_ID, THREAD_ID, "retention-ttl-message");
        jdbc.update("""
                INSERT INTO mail_attachment
                    (id, message_id, gmail_attachment_id, filename, mime_type, byte_size,
                     fetched_at, expires_at, content_nonce, content_ciphertext)
                VALUES (?, ?, 'retention-ttl-attachment', 'synthetic.bin', 'application/octet-stream', 16,
                        CURRENT_TIMESTAMP - INTERVAL '1 day', CURRENT_TIMESTAMP + INTERVAL '6 days',
                        decode(repeat('33', 12), 'hex'), decode(repeat('cc', 32), 'hex'))
                """, ATTACHMENT_ID, MESSAGE_ID);
        jdbc.update("""
                INSERT INTO summary_run
                    (id, app_user_id, period_start, period_end, run_status,
                     run_purpose, coverage_status, eligible_message_count, selected_message_count,
                     additional_message_count, read_expansion_approved,
                     narrative_nonce, narrative_ciphertext, expires_at)
                VALUES (?, ?, CURRENT_TIMESTAMP - INTERVAL '2 days', CURRENT_TIMESTAMP - INTERVAL '1 day',
                        'COMPLETED', 'MANUAL', 'COMPLETE', 1, 1, 0, FALSE,
                        decode(repeat('44', 12), 'hex'), decode(repeat('dd', 32), 'hex'),
                        CURRENT_TIMESTAMP + INTERVAL '6 days')
                """, SUMMARY_ID, USER_ID);
        jdbc.update("""
                INSERT INTO summary_run_mailbox (summary_run_id, app_user_id, mailbox_id)
                VALUES (?, ?, ?)
                """, SUMMARY_ID, USER_ID, MAILBOX_ID);
        jdbc.update("""
                INSERT INTO summary_run_source (summary_run_id, app_user_id, mailbox_id, message_id)
                VALUES (?, ?, ?, ?)
                """, SUMMARY_ID, USER_ID, MAILBOX_ID, MESSAGE_ID);
        jdbc.update("""
                INSERT INTO assistant_insight
                    (id, summary_run_id, app_user_id, insight_kind, confidence, urgency,
                     content_nonce, content_ciphertext, expires_at)
                VALUES (?, ?, ?, 'URGENT_ACTION', 0.9, 'HIGH',
                        decode(repeat('55', 12), 'hex'), decode(repeat('ee', 32), 'hex'),
                        CURRENT_TIMESTAMP + INTERVAL '6 days')
                """, INSIGHT_ID, SUMMARY_ID, USER_ID);
        jdbc.update("""
                INSERT INTO assistant_insight_source
                    (insight_id, message_id, summary_run_id, app_user_id, mailbox_id)
                VALUES (?, ?, ?, ?, ?)
                """, INSIGHT_ID, MESSAGE_ID, SUMMARY_ID, USER_ID, MAILBOX_ID);

        jdbc.update("""
                INSERT INTO mail_thread
                    (id, mailbox_id, gmail_thread_id, subject, last_message_at, message_count,
                     snippet_nonce, snippet_ciphertext, snippet_expires_at)
                VALUES (?, ?, ?, 'synthetic second mailbox', CURRENT_TIMESTAMP, 1,
                        decode(repeat('66', 12), 'hex'), decode(repeat('ff', 32), 'hex'),
                        CURRENT_TIMESTAMP + INTERVAL '6 days')
                """, SECOND_THREAD_ID, SECOND_MAILBOX_ID, "retention-ttl-second-thread");
        jdbc.update("""
                INSERT INTO mail_message
                    (id, mailbox_id, thread_id, gmail_message_id, sender_email, sent_at,
                     body_mime_type, body_nonce, body_ciphertext, body_cached_at, body_expires_at)
                VALUES (?, ?, ?, ?, 'second-sender@example.invalid', CURRENT_TIMESTAMP,
                        'text/plain', decode(repeat('77', 12), 'hex'), decode(repeat('ab', 32), 'hex'),
                        CURRENT_TIMESTAMP - INTERVAL '1 day', CURRENT_TIMESTAMP + INTERVAL '6 days')
                """, SECOND_MESSAGE_ID, SECOND_MAILBOX_ID, SECOND_THREAD_ID, "retention-ttl-second-message");
    }

    private static void seedDisconnectRows(JdbcTemplate jdbc) {
        insertReport(jdbc, COMBINED_SUMMARY_ID, "MANUAL", "COMPLETE", 2, 2);
        insertReport(jdbc, EMPTY_SCOPE_SUMMARY_ID, "MANUAL", "COMPLETE", 0, 0);
        jdbc.update("""
                INSERT INTO summary_run_mailbox (summary_run_id, app_user_id, mailbox_id)
                VALUES (?, ?, ?), (?, ?, ?), (?, ?, ?)
                """, COMBINED_SUMMARY_ID, USER_ID, MAILBOX_ID,
                COMBINED_SUMMARY_ID, USER_ID, SECOND_MAILBOX_ID,
                EMPTY_SCOPE_SUMMARY_ID, USER_ID, MAILBOX_ID);
        jdbc.update("""
                INSERT INTO summary_run_source (summary_run_id, app_user_id, mailbox_id, message_id)
                VALUES (?, ?, ?, ?), (?, ?, ?, ?)
                """, COMBINED_SUMMARY_ID, USER_ID, MAILBOX_ID, MESSAGE_ID,
                COMBINED_SUMMARY_ID, USER_ID, SECOND_MAILBOX_ID, SECOND_MESSAGE_ID);
        jdbc.update("""
                INSERT INTO assistant_insight
                    (id, summary_run_id, app_user_id, insight_kind, confidence,
                     content_nonce, content_ciphertext, expires_at)
                VALUES (?, ?, ?, 'REPLY_RECEIVED', 0.8,
                        decode(repeat('88', 12), 'hex'), decode(repeat('ac', 32), 'hex'),
                        CURRENT_TIMESTAMP + INTERVAL '6 days')
                """, COMBINED_INSIGHT_ID, COMBINED_SUMMARY_ID, USER_ID);
        jdbc.update("""
                INSERT INTO assistant_insight_source
                    (insight_id, message_id, summary_run_id, app_user_id, mailbox_id)
                VALUES (?, ?, ?, ?, ?)
                """, COMBINED_INSIGHT_ID, MESSAGE_ID, COMBINED_SUMMARY_ID, USER_ID, MAILBOX_ID);
    }

    private static void insertReport(
            JdbcTemplate jdbc, UUID id, String purpose, String coverage, int eligible, int selected) {
        jdbc.update("""
                INSERT INTO summary_run
                    (id, app_user_id, period_start, period_end, run_status,
                     run_purpose, coverage_status, eligible_message_count, selected_message_count,
                     additional_message_count, read_expansion_approved,
                     narrative_nonce, narrative_ciphertext, expires_at)
                VALUES (?, ?, CURRENT_TIMESTAMP - INTERVAL '2 days', CURRENT_TIMESTAMP - INTERVAL '1 day',
                        'COMPLETED', ?, ?, ?, ?, 0, FALSE,
                        decode(repeat('99', 12), 'hex'), decode(repeat('ad', 32), 'hex'),
                        CURRENT_TIMESTAMP + INTERVAL '6 days')
                """, id, USER_ID, purpose, coverage, eligible, selected);
    }

    private static int count(JdbcTemplate jdbc, String table, String idColumn, UUID id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + idColumn + " = ?",
                Integer.class,
                id);
    }

    private static void assertExpiryDays(
            JdbcTemplate jdbc, String table, String startColumn, String expiryColumn, UUID id, int expectedDays) {
        String sql = "SELECT EXTRACT(EPOCH FROM (" + expiryColumn + " - " + startColumn + ")) / 86400.0 "
                + "FROM " + table + " WHERE id = ?";
        Double days = jdbc.queryForObject(sql, Double.class, id);
        assertTrue(days != null, "expiry row must exist");
        assertEquals((double) expectedDays, days, 0.000001,
                table + "." + expiryColumn + " should match the selected TTL");
    }

    private static void assertSnippetExpiryDays(JdbcTemplate jdbc, int expectedDays) {
        Double days = jdbc.queryForObject("""
                SELECT EXTRACT(EPOCH FROM (mt.snippet_expires_at - mm.body_cached_at)) / 86400.0
                FROM mail_thread mt
                JOIN mail_message mm ON mm.thread_id = mt.id
                WHERE mt.id = ?
                """, Double.class, THREAD_ID);
        assertTrue(days != null, "thread snippet expiry row must exist");
        assertEquals((double) expectedDays, days, 0.000001,
                "mail_thread.snippet_expires_at should match the selected TTL");
    }

    private static void assertSourceExpiryMatches(JdbcTemplate jdbc) {
        Boolean summaryMatches = jdbc.queryForObject("""
                SELECT sr.expires_at = mm.body_expires_at
                FROM summary_run sr
                JOIN summary_run_source srs ON srs.summary_run_id = sr.id
                JOIN mail_message mm ON mm.id = srs.message_id
                WHERE sr.id = ?
                """, Boolean.class, SUMMARY_ID);
        Boolean insightMatches = jdbc.queryForObject("""
                SELECT ai.expires_at = mm.body_expires_at
                FROM assistant_insight ai
                JOIN assistant_insight_source ais ON ais.insight_id = ai.id
                JOIN mail_message mm ON mm.id = ais.message_id
                WHERE ai.id = ?
                """, Boolean.class, INSIGHT_ID);
        assertEquals(Boolean.TRUE, summaryMatches);
        assertEquals(Boolean.TRUE, insightMatches);
    }
}

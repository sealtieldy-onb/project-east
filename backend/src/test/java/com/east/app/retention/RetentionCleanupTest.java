package com.east.app.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class RetentionCleanupTest {

    @Test
    void repositoryTargetsExpiredDerivedAndCachedContentWithoutDeletingMailMetadata() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        RetentionCleanupRepository repository = new RetentionCleanupRepository(jdbc);
        Timestamp cutoff = Timestamp.from(Instant.parse("2026-11-09T00:00:00Z"));

        repository.deleteExpiredSummaryRuns(cutoff);
        repository.deleteExpiredInsights(cutoff);
        repository.deleteExpiredAttachments(cutoff);
        repository.clearExpiredMessageBodies(cutoff);
        repository.clearExpiredThreadSnippets(cutoff);

        assertEquals(5, jdbc.updates.size());
        assertUpdate(jdbc, 0, cutoff,
                "DELETE FROM summary_run sr",
                "sr.expires_at <= ?",
                "mm.body_expires_at <= ?",
                "JOIN assistant_insight_source ais");
        assertUpdate(jdbc, 1, cutoff,
                "DELETE FROM assistant_insight ai",
                "ai.expires_at <= ?",
                "mm.body_expires_at <= ?");
        assertUpdate(jdbc, 2, cutoff,
                "DELETE FROM mail_attachment ma",
                "ma.expires_at <= ?",
                "mm.body_expires_at <= ?",
                "ma.fetched_at IS NULL OR ma.fetched_at <= mm.body_expires_at");
        assertUpdate(jdbc, 3, cutoff,
                "UPDATE mail_message",
                "body_mime_type = NULL",
                "body_nonce = NULL",
                "body_ciphertext = NULL",
                "body_cached_at = NULL",
                "body_expires_at = NULL",
                "WHERE body_expires_at IS NOT NULL AND body_expires_at <= ?");
        assertUpdate(jdbc, 4, cutoff,
                "UPDATE mail_thread",
                "snippet_nonce = NULL",
                "snippet_ciphertext = NULL",
                "snippet_expires_at = NULL",
                "WHERE snippet_expires_at IS NOT NULL AND snippet_expires_at <= ?");
        assertFalse(normalize(jdbc.updates.get(3).sql()).contains("DELETE FROM mail_message"));
        assertFalse(normalize(jdbc.updates.get(4).sql()).contains("DELETE FROM mail_thread"));
    }

    @Test
    void serviceUsesOneClockCutoffForEveryCleanupStep() {
        Instant now = Instant.parse("2026-11-09T12:34:56Z");
        RecordingRepository repository = new RecordingRepository();
        RetentionCleanupService service = new RetentionCleanupService(
                repository,
                Clock.fixed(now, ZoneOffset.UTC));

        service.purgeExpiredData();

        Timestamp expected = Timestamp.from(now);
        assertEquals(List.of(
                "summary-runs", "insights", "attachments", "message-bodies", "thread-snippets"),
                repository.operations);
        assertEquals(List.of(expected, expected, expected, expected, expected), repository.cutoffs);
    }

    private static void assertUpdate(
            CapturingJdbcTemplate jdbc,
            int index,
            Timestamp cutoff,
            String... expectedSqlFragments) {
        UpdateCall call = jdbc.updates.get(index);
        String sql = normalize(call.sql());
        for (String fragment : expectedSqlFragments) {
            assertTrue(sql.contains(normalize(fragment)), "Expected SQL fragment: " + fragment);
        }
        for (Object argument : call.arguments()) assertEquals(cutoff, argument);
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private record UpdateCall(String sql, Object[] arguments) { }

    private static final class CapturingJdbcTemplate extends JdbcTemplate {
        private final List<UpdateCall> updates = new ArrayList<>();

        @Override
        public int update(String sql, Object... arguments) {
            updates.add(new UpdateCall(sql, Arrays.copyOf(arguments, arguments.length)));
            return 1;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper) {
            return List.of();
        }
    }

    private static final class RecordingRepository extends RetentionCleanupRepository {
        private final List<String> operations = new ArrayList<>();
        private final List<Timestamp> cutoffs = new ArrayList<>();

        private RecordingRepository() {
            super(null);
        }

        @Override
        int deleteExpiredSummaryRuns(Timestamp cutoff) {
            operations.add("summary-runs");
            cutoffs.add(cutoff);
            return 0;
        }

        @Override
        int deleteExpiredInsights(Timestamp cutoff) {
            operations.add("insights");
            cutoffs.add(cutoff);
            return 0;
        }

        @Override
        int deleteExpiredAttachments(Timestamp cutoff) {
            operations.add("attachments");
            cutoffs.add(cutoff);
            return 0;
        }

        @Override
        int clearExpiredMessageBodies(Timestamp cutoff) {
            operations.add("message-bodies");
            cutoffs.add(cutoff);
            return 0;
        }

        @Override
        int clearExpiredThreadSnippets(Timestamp cutoff) {
            operations.add("thread-snippets");
            cutoffs.add(cutoff);
            return 0;
        }
    }
}

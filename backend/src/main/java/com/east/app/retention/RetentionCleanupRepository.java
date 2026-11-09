package com.east.app.retention;

import java.sql.Timestamp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Database operations for removing encrypted cache data after its stored expiry. */
@Repository
public class RetentionCleanupRepository {

    private final JdbcTemplate jdbcTemplate;

    public RetentionCleanupRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    int deleteExpiredSummaryRuns(Timestamp cutoff) {
        return jdbcTemplate.update("""
                DELETE FROM summary_run sr
                WHERE (sr.expires_at IS NOT NULL AND sr.expires_at <= ?)
                   OR EXISTS (
                        SELECT 1
                        FROM summary_run_source srs
                        JOIN mail_message mm ON mm.id = srs.message_id
                        WHERE srs.summary_run_id = sr.id
                          AND mm.body_expires_at IS NOT NULL
                          AND mm.body_expires_at <= ?
                   )
                   OR EXISTS (
                        SELECT 1
                        FROM assistant_insight ai
                        JOIN assistant_insight_source ais ON ais.insight_id = ai.id
                        JOIN mail_message mm ON mm.id = ais.message_id
                        WHERE ai.summary_run_id = sr.id
                          AND mm.body_expires_at IS NOT NULL
                          AND mm.body_expires_at <= ?
                   )
                """, cutoff, cutoff, cutoff);
    }

    int deleteExpiredInsights(Timestamp cutoff) {
        return jdbcTemplate.update("""
                DELETE FROM assistant_insight ai
                WHERE ai.expires_at <= ?
                   OR EXISTS (
                        SELECT 1
                        FROM assistant_insight_source ais
                        JOIN mail_message mm ON mm.id = ais.message_id
                        WHERE ais.insight_id = ai.id
                          AND mm.body_expires_at IS NOT NULL
                          AND mm.body_expires_at <= ?
                   )
                """, cutoff, cutoff);
    }

    int deleteExpiredAttachments(Timestamp cutoff) {
        return jdbcTemplate.update("""
                DELETE FROM mail_attachment ma
                USING mail_message mm
                WHERE ma.message_id = mm.id
                  AND (
                       (ma.expires_at IS NOT NULL AND ma.expires_at <= ?)
                       OR (
                            mm.body_expires_at IS NOT NULL
                            AND mm.body_expires_at <= ?
                            AND (ma.fetched_at IS NULL OR ma.fetched_at <= mm.body_expires_at)
                       )
                  )
                """, cutoff, cutoff);
    }

    int clearExpiredMessageBodies(Timestamp cutoff) {
        return jdbcTemplate.update("""
                UPDATE mail_message
                SET body_mime_type = NULL,
                    body_nonce = NULL,
                    body_ciphertext = NULL,
                    body_cached_at = NULL,
                    body_expires_at = NULL
                WHERE body_expires_at IS NOT NULL
                  AND body_expires_at <= ?
                """, cutoff);
    }

    int clearExpiredThreadSnippets(Timestamp cutoff) {
        return jdbcTemplate.update("""
                UPDATE mail_thread
                SET snippet_nonce = NULL,
                    snippet_ciphertext = NULL,
                    snippet_expires_at = NULL
                WHERE snippet_expires_at IS NOT NULL
                  AND snippet_expires_at <= ?
                """, cutoff);
    }
}

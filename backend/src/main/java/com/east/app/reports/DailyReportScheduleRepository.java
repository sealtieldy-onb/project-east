package com.east.app.reports;

import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DailyReportScheduleRepository {

    private final JdbcTemplate jdbcTemplate;

    public DailyReportScheduleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ScheduleRow> find(UUID appUserId) {
        List<ScheduleRow> rows = jdbcTemplate.query(
                """
                SELECT enabled, run_at_local, timezone_id, provider_disclosure_version,
                       last_attempted_report_date, last_successful_report_date, updated_at
                FROM summary_schedule WHERE app_user_id = ?
                """,
                (rs, row) -> new ScheduleRow(
                        null,
                        rs.getBoolean("enabled"),
                        rs.getTime("run_at_local").toLocalTime(),
                        rs.getString("timezone_id"),
                        rs.getString("provider_disclosure_version"),
                        rs.getObject("last_attempted_report_date", LocalDate.class),
                        rs.getObject("last_successful_report_date", LocalDate.class),
                        rs.getTimestamp("updated_at").toInstant()),
                appUserId);
        return rows.stream().findFirst();
    }

    public List<ScheduleRow> findEnabled() {
        return jdbcTemplate.query(
                """
                SELECT app_user_id, enabled, run_at_local, timezone_id, provider_disclosure_version,
                       last_attempted_report_date, last_successful_report_date, updated_at
                FROM summary_schedule WHERE enabled = TRUE
                ORDER BY app_user_id
                """,
                (rs, row) -> new ScheduleRow(
                        rs.getObject("app_user_id", UUID.class),
                        rs.getBoolean("enabled"),
                        rs.getTime("run_at_local").toLocalTime(),
                        rs.getString("timezone_id"),
                        rs.getString("provider_disclosure_version"),
                        rs.getObject("last_attempted_report_date", LocalDate.class),
                        rs.getObject("last_successful_report_date", LocalDate.class),
                        rs.getTimestamp("updated_at").toInstant()));
    }

    public void save(
            UUID appUserId,
            boolean enabled,
            LocalTime runAtLocal,
            String timezoneId,
            String providerDisclosureVersion) {
        jdbcTemplate.update(
                """
                INSERT INTO summary_schedule (
                    id, app_user_id, enabled, run_at_local, timezone_id,
                    provider_disclosure_version, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (app_user_id) DO UPDATE SET
                    enabled = EXCLUDED.enabled,
                    run_at_local = EXCLUDED.run_at_local,
                    timezone_id = EXCLUDED.timezone_id,
                    provider_disclosure_version = CASE
                        WHEN EXCLUDED.enabled THEN EXCLUDED.provider_disclosure_version
                        ELSE summary_schedule.provider_disclosure_version
                    END,
                    updated_at = CURRENT_TIMESTAMP
                """,
                UUID.randomUUID(), appUserId, enabled, Time.valueOf(runAtLocal), timezoneId,
                enabled ? providerDisclosureVersion : null);
    }

    /** Atomically claims one local report date so concurrent scheduler ticks cannot duplicate it. */
    public boolean claimAttempt(UUID appUserId, LocalDate reportDate, java.time.Instant attemptedAt) {
        return jdbcTemplate.update(
                """
                UPDATE summary_schedule
                SET last_attempted_report_date = ?, last_attempt_at = ?, updated_at = CURRENT_TIMESTAMP
                WHERE app_user_id = ? AND enabled = TRUE
                  AND (last_attempted_report_date IS NULL OR last_attempted_report_date < ?)
                """,
                reportDate, Timestamp.from(attemptedAt), appUserId, reportDate) == 1;
    }

    public void recordSuccess(UUID appUserId, LocalDate reportDate, java.time.Instant succeededAt) {
        jdbcTemplate.update(
                """
                UPDATE summary_schedule
                SET last_successful_report_date = ?, last_success_at = ?, updated_at = CURRENT_TIMESTAMP
                WHERE app_user_id = ? AND last_attempted_report_date = ?
                """,
                reportDate, Timestamp.from(succeededAt), appUserId, reportDate);
    }

    public void pause(UUID appUserId) {
        jdbcTemplate.update(
                "UPDATE summary_schedule SET enabled = FALSE, updated_at = CURRENT_TIMESTAMP WHERE app_user_id = ?",
                appUserId);
    }

    public record ScheduleRow(
            UUID appUserId,
            boolean enabled,
            LocalTime runAtLocal,
            String timezoneId,
            String providerDisclosureVersion,
            LocalDate lastAttemptedReportDate,
            LocalDate lastSuccessfulReportDate,
            java.time.Instant updatedAt) {
    }
}

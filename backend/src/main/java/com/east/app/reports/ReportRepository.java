package com.east.app.reports;

import com.east.app.reports.ReportModels.InsightStatus;
import com.east.app.reports.ReportModels.MailboxScope;
import com.east.app.reports.ReportModels.ReportCoverage;
import com.east.app.reports.ReportModels.ReportSource;
import com.east.app.reports.ReportModels.SummaryResponse;
import com.east.app.vault.EncryptedPayload;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Ownership-scoped report reads and atomic encrypted report/provenance writes. */
@Repository
public class ReportRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReportRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<MailboxScope> findOwnedMailboxes(UUID appUserId, List<UUID> requestedIds) {
        if (requestedIds.isEmpty()) {
            return jdbcTemplate.query(
                    """
                    SELECT id, email, display_name, account_color_index
                    FROM connected_mailbox
                    WHERE app_user_id = ?
                    ORDER BY created_at, id
                    """,
                    (rs, row) -> new MailboxScope(
                            rs.getObject("id", UUID.class), rs.getString("email"),
                            rs.getString("display_name"), rs.getInt("account_color_index")),
                    appUserId);
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(requestedIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(appUserId);
        args.addAll(requestedIds);
        return jdbcTemplate.query(
                """
                SELECT id, email, display_name, account_color_index
                FROM connected_mailbox
                WHERE app_user_id = ? AND id IN (""" + placeholders + ") ORDER BY created_at, id",
                (rs, row) -> new MailboxScope(
                        rs.getObject("id", UUID.class), rs.getString("email"),
                        rs.getString("display_name"), rs.getInt("account_color_index")),
                args.toArray());
    }

    public List<ReportInputMessage> findReportInputs(
            UUID appUserId,
            UUID mailboxId,
            List<String> gmailMessageIds,
            Instant startInclusive,
            Instant endExclusive,
            Instant now) {
        if (gmailMessageIds.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(gmailMessageIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(appUserId);
        args.add(mailboxId);
        args.add(Timestamp.from(startInclusive));
        args.add(Timestamp.from(endExclusive));
        args.add(Timestamp.from(now));
        args.addAll(gmailMessageIds);
        return jdbcTemplate.query(
                """
                SELECT mm.id AS message_id, mm.mailbox_id, cm.email AS account_email,
                       mt.id AS thread_id, mt.gmail_thread_id, mt.subject,
                       mm.gmail_message_id, mm.sender_email, mm.sender_name, mm.sent_at,
                       mm.body_mime_type, mm.body_nonce, mm.body_ciphertext, mm.body_expires_at
                FROM mail_message mm
                JOIN connected_mailbox cm ON cm.id = mm.mailbox_id AND cm.app_user_id = ?
                JOIN mail_thread mt ON mt.id = mm.thread_id AND mt.mailbox_id = mm.mailbox_id
                WHERE mm.mailbox_id = ?
                  AND mm.sent_at >= ? AND mm.sent_at < ?
                  AND mm.body_mime_type = 'application/json'
                  AND mm.body_nonce IS NOT NULL AND mm.body_ciphertext IS NOT NULL
                  AND mm.body_expires_at > ?
                  AND mm.gmail_message_id IN (""" + placeholders + ") ORDER BY mm.sent_at DESC, mm.id",
                (rs, row) -> new ReportInputMessage(
                        rs.getObject("message_id", UUID.class),
                        rs.getObject("mailbox_id", UUID.class),
                        rs.getString("account_email"),
                        rs.getObject("thread_id", UUID.class),
                        rs.getString("gmail_thread_id"),
                        rs.getString("subject"),
                        rs.getString("gmail_message_id"),
                        rs.getString("sender_email"),
                        rs.getString("sender_name"),
                        rs.getTimestamp("sent_at").toInstant(),
                        rs.getBytes("body_nonce"),
                        rs.getBytes("body_ciphertext"),
                        rs.getTimestamp("body_expires_at").toInstant()),
                args.toArray());
    }

    public List<ReportInputMessage> findCachedReportInputs(
            UUID appUserId,
            List<UUID> mailboxIds,
            Instant startInclusive,
            Instant endExclusive,
            Instant now,
            int limit) {
        if (mailboxIds.isEmpty() || limit < 1) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(mailboxIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(appUserId);
        args.addAll(mailboxIds);
        args.add(Timestamp.from(startInclusive));
        args.add(Timestamp.from(endExclusive));
        args.add(Timestamp.from(now));
        args.add(limit);
        args.add(limit);
        return jdbcTemplate.query(
                """
                WITH eligible AS (
                    SELECT mm.id AS message_id, mm.mailbox_id, cm.email AS account_email,
                           mt.id AS thread_id, mt.gmail_thread_id, mt.subject,
                           mm.gmail_message_id, mm.sender_email, mm.sender_name, mm.sent_at,
                           mm.body_nonce, mm.body_ciphertext, mm.body_expires_at,
                           ROW_NUMBER() OVER (PARTITION BY mm.mailbox_id ORDER BY mm.sent_at DESC, mm.id) AS rank
                    FROM mail_message mm
                    JOIN connected_mailbox cm ON cm.id = mm.mailbox_id AND cm.app_user_id = ?
                    JOIN mail_thread mt ON mt.id = mm.thread_id AND mt.mailbox_id = mm.mailbox_id
                    WHERE mm.mailbox_id IN (""" + placeholders + """
                      ) AND mm.sent_at >= ? AND mm.sent_at < ?
                      AND mm.body_mime_type = 'application/json'
                      AND mm.body_nonce IS NOT NULL AND mm.body_ciphertext IS NOT NULL
                      AND mm.body_expires_at > ?
                )
                SELECT * FROM eligible
                WHERE rank <= ?
                ORDER BY rank, sent_at DESC, mailbox_id, message_id
                LIMIT ?
                """,
                (rs, row) -> new ReportInputMessage(
                        rs.getObject("message_id", UUID.class), rs.getObject("mailbox_id", UUID.class),
                        rs.getString("account_email"), rs.getObject("thread_id", UUID.class),
                        rs.getString("gmail_thread_id"), rs.getString("subject"),
                        rs.getString("gmail_message_id"), rs.getString("sender_email"),
                        rs.getString("sender_name"), rs.getTimestamp("sent_at").toInstant(),
                        rs.getBytes("body_nonce"), rs.getBytes("body_ciphertext"),
                        rs.getTimestamp("body_expires_at").toInstant()),
                args.toArray());
    }

    public int countCachedReportInputs(
            UUID appUserId,
            List<UUID> mailboxIds,
            Instant startInclusive,
            Instant endExclusive,
            Instant now) {
        if (mailboxIds.isEmpty()) return 0;
        String placeholders = String.join(",", java.util.Collections.nCopies(mailboxIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(appUserId);
        args.addAll(mailboxIds);
        args.add(Timestamp.from(startInclusive));
        args.add(Timestamp.from(endExclusive));
        args.add(Timestamp.from(now));
        String sql = """
                SELECT COUNT(*)::INTEGER
                FROM mail_message mm
                JOIN connected_mailbox cm ON cm.id = mm.mailbox_id AND cm.app_user_id = ?
                WHERE mm.mailbox_id IN (%s)
                  AND mm.sent_at >= ? AND mm.sent_at < ?
                  AND mm.body_mime_type = 'application/json'
                  AND mm.body_nonce IS NOT NULL AND mm.body_ciphertext IS NOT NULL
                  AND mm.body_expires_at > ?
                """.formatted(placeholders);
        Integer count = jdbcTemplate.queryForObject(
                sql,
                Integer.class,
                args.toArray());
        return count == null ? 0 : count;
    }

    public int countMessageMetadata(
            UUID appUserId,
            List<UUID> mailboxIds,
            Instant startInclusive,
            Instant endExclusive) {
        if (mailboxIds.isEmpty()) return 0;
        String placeholders = String.join(",", java.util.Collections.nCopies(mailboxIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(appUserId);
        args.addAll(mailboxIds);
        args.add(Timestamp.from(startInclusive));
        args.add(Timestamp.from(endExclusive));
        String sql = """
                SELECT COUNT(*)::INTEGER FROM mail_message mm
                JOIN connected_mailbox cm ON cm.id = mm.mailbox_id AND cm.app_user_id = ?
                WHERE mm.mailbox_id IN (%s)
                  AND mm.sent_at >= ? AND mm.sent_at < ?
                """.formatted(placeholders);
        Integer count = jdbcTemplate.queryForObject(
                sql,
                Integer.class,
                args.toArray());
        return count == null ? 0 : count;
    }

    public boolean hasMailboxAtInitialSyncLimit(
            UUID appUserId,
            List<UUID> mailboxIds,
            Instant startInclusive,
            Instant endExclusive,
            int initialLimit) {
        if (mailboxIds.isEmpty()) return false;
        String placeholders = String.join(",", java.util.Collections.nCopies(mailboxIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(appUserId);
        args.addAll(mailboxIds);
        args.add(Timestamp.from(startInclusive));
        args.add(Timestamp.from(endExclusive));
        args.add(initialLimit);
        String sql = """
                SELECT EXISTS (
                    SELECT 1 FROM mail_message mm
                    JOIN connected_mailbox cm ON cm.id = mm.mailbox_id AND cm.app_user_id = ?
                    WHERE mm.mailbox_id IN (%s)
                      AND mm.sent_at >= ? AND mm.sent_at < ?
                    GROUP BY mm.mailbox_id HAVING COUNT(*) >= ?
                )
                """.formatted(placeholders);
        Boolean capped = jdbcTemplate.queryForObject(
                sql,
                Boolean.class,
                args.toArray());
        return Boolean.TRUE.equals(capped);
    }

    public boolean hasScheduledReport(UUID appUserId, LocalDate reportDate) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM summary_run WHERE app_user_id = ? AND scheduled_report_date = ?)",
                Boolean.class,
                appUserId,
                reportDate);
        return Boolean.TRUE.equals(exists);
    }

    public int bodyCacheTtlDays(UUID appUserId) {
        Integer days = jdbcTemplate.queryForObject(
                "SELECT body_cache_ttl_days FROM app_user WHERE id = ?",
                Integer.class, appUserId);
        if (days == null || days < 1 || days > 365) {
            throw new IllegalStateException("The configured message cache lifetime is invalid.");
        }
        return days;
    }

    public boolean hasReportForAnyMailbox(UUID appUserId, List<UUID> mailboxIds) {
        if (mailboxIds.isEmpty()) return false;
        String placeholders = String.join(",", java.util.Collections.nCopies(mailboxIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(appUserId);
        args.addAll(mailboxIds);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM summary_run_mailbox WHERE app_user_id = ? AND mailbox_id IN (" + placeholders + ")",
                Integer.class,
                args.toArray());
        return count != null && count > 0;
    }

    public boolean hasMailboxWithoutReport(UUID appUserId, List<UUID> mailboxIds) {
        if (mailboxIds.isEmpty()) return false;
        String placeholders = String.join(",", java.util.Collections.nCopies(mailboxIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(appUserId);
        args.addAll(mailboxIds);
        String sql = """
                SELECT EXISTS (
                    SELECT 1 FROM connected_mailbox cm
                    WHERE cm.app_user_id = ? AND cm.id IN (%s)
                      AND NOT EXISTS (
                          SELECT 1 FROM summary_run_mailbox srm
                          WHERE srm.app_user_id = cm.app_user_id AND srm.mailbox_id = cm.id
                      )
                )
                """.formatted(placeholders);
        Boolean exists = jdbcTemplate.queryForObject(
                sql,
                Boolean.class,
                args.toArray());
        return Boolean.TRUE.equals(exists);
    }

    public Optional<ReportRunRow> findLatestReport(
            UUID appUserId,
            Instant startInclusive,
            Instant endExclusive,
            LocalDate requestedStartDate,
            LocalDate requestedEndDate,
            List<UUID> requestedMailboxIds,
            Instant now) {
        List<ReportRunRow> candidates = jdbcTemplate.query(
                """
                SELECT id, run_status, period_start, period_end, narrative_nonce, narrative_ciphertext,
                       expires_at, created_at, coverage_status, eligible_message_count,
                       selected_message_count, additional_message_count, provider_type, provider_model_id,
                       scheduled_report_date
                FROM summary_run
                WHERE app_user_id = ?
                  AND ((period_start = ? AND period_end = ?)
                       OR scheduled_report_date BETWEEN ? AND ?)
                  AND run_status = 'COMPLETED'
                  AND narrative_ciphertext IS NOT NULL AND expires_at > ?
                ORDER BY created_at DESC, id DESC
                """,
                (rs, row) -> new ReportRunRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("run_status"),
                        rs.getTimestamp("period_start").toInstant(),
                        rs.getTimestamp("period_end").toInstant(),
                        rs.getBytes("narrative_nonce"),
                        rs.getBytes("narrative_ciphertext"),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("coverage_status"),
                        rs.getInt("eligible_message_count"),
                        rs.getInt("selected_message_count"),
                        rs.getInt("additional_message_count"),
                        rs.getString("provider_type"),
                        rs.getString("provider_model_id"),
                        rs.getObject("scheduled_report_date", LocalDate.class)),
                appUserId,
                Timestamp.from(startInclusive),
                Timestamp.from(endExclusive),
                requestedStartDate,
                requestedEndDate,
                Timestamp.from(now));
        Set<UUID> expected = Set.copyOf(requestedMailboxIds);
        for (ReportRunRow candidate : candidates) {
            List<UUID> mailboxIds = findRunMailboxIds(candidate.id());
            if (new HashSet<>(mailboxIds).equals(expected)) return Optional.of(candidate);
            candidate.destroy();
        }
        return Optional.empty();
    }

    public Optional<ReportRunRow> findOwnedReport(UUID appUserId, UUID reportId, Instant now) {
        List<ReportRunRow> rows = jdbcTemplate.query(
                """
                SELECT id, run_status, period_start, period_end, narrative_nonce, narrative_ciphertext,
                       expires_at, created_at, coverage_status, eligible_message_count,
                       selected_message_count, additional_message_count, provider_type, provider_model_id,
                       scheduled_report_date
                FROM summary_run
                WHERE id = ? AND app_user_id = ? AND run_status = 'COMPLETED'
                  AND narrative_ciphertext IS NOT NULL AND expires_at > ?
                """,
                (rs, row) -> new ReportRunRow(
                        rs.getObject("id", UUID.class), rs.getString("run_status"),
                        rs.getTimestamp("period_start").toInstant(), rs.getTimestamp("period_end").toInstant(),
                        rs.getBytes("narrative_nonce"), rs.getBytes("narrative_ciphertext"),
                        rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("created_at").toInstant(),
                        rs.getString("coverage_status"), rs.getInt("eligible_message_count"),
                        rs.getInt("selected_message_count"), rs.getInt("additional_message_count"),
                        rs.getString("provider_type"), rs.getString("provider_model_id"),
                        rs.getObject("scheduled_report_date", LocalDate.class)),
                reportId, appUserId, Timestamp.from(now));
        return rows.stream().findFirst();
    }

    public List<MailboxScope> findRunMailboxes(UUID appUserId, UUID reportId) {
        return jdbcTemplate.query(
                """
                SELECT cm.id, cm.email, cm.display_name, cm.account_color_index
                FROM summary_run_mailbox srm
                JOIN connected_mailbox cm ON cm.id = srm.mailbox_id AND cm.app_user_id = srm.app_user_id
                WHERE srm.summary_run_id = ? AND srm.app_user_id = ?
                ORDER BY cm.created_at, cm.id
                """,
                (rs, row) -> new MailboxScope(
                        rs.getObject("id", UUID.class), rs.getString("email"),
                        rs.getString("display_name"), rs.getInt("account_color_index")),
                reportId, appUserId);
    }

    public List<InsightRow> findInsights(UUID appUserId, UUID reportId) {
        return jdbcTemplate.query(
                """
                SELECT ai.id, ai.insight_kind, ai.insight_status, ai.confidence,
                       ai.content_nonce, ai.content_ciphertext, ai.expires_at
                FROM assistant_insight ai
                JOIN summary_run sr ON sr.id = ai.summary_run_id
                WHERE ai.summary_run_id = ? AND sr.app_user_id = ?
                ORDER BY ai.created_at, ai.id
                """,
                (rs, row) -> new InsightRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("insight_kind"),
                        InsightStatus.valueOf(rs.getString("insight_status")),
                        rs.getBigDecimal("confidence").doubleValue(),
                        rs.getBytes("content_nonce"),
                        rs.getBytes("content_ciphertext"),
                        rs.getTimestamp("expires_at").toInstant()),
                reportId, appUserId);
    }

    public List<ReportSource> findInsightSources(UUID appUserId, UUID insightId) {
        return jdbcTemplate.query(
                """
                SELECT mm.mailbox_id, mm.thread_id, mm.id AS message_id, cm.email AS account_email, mm.sent_at
                FROM assistant_insight_source ais
                JOIN assistant_insight ai ON ai.id = ais.insight_id
                    AND ai.summary_run_id = ais.summary_run_id AND ai.app_user_id = ais.app_user_id
                JOIN summary_run sr ON sr.id = ais.summary_run_id AND sr.app_user_id = ais.app_user_id
                JOIN mail_message mm ON mm.id = ais.message_id AND mm.mailbox_id = ais.mailbox_id
                JOIN connected_mailbox cm ON cm.id = ais.mailbox_id AND cm.app_user_id = ais.app_user_id
                WHERE ais.insight_id = ? AND ais.app_user_id = ?
                ORDER BY mm.sent_at, mm.id
                """,
                (rs, row) -> new ReportSource(
                        rs.getObject("mailbox_id", UUID.class),
                        rs.getObject("thread_id", UUID.class),
                        rs.getObject("message_id", UUID.class),
                        rs.getString("account_email"),
                        rs.getTimestamp("sent_at").toInstant()),
                insightId, appUserId);
    }

    public boolean updateInsightStatus(UUID appUserId, UUID insightId, InsightStatus status) {
        return jdbcTemplate.update(
                """
                UPDATE assistant_insight ai
                SET insight_status = ?
                FROM summary_run sr
                WHERE ai.summary_run_id = sr.id AND ai.app_user_id = sr.app_user_id
                  AND ai.id = ? AND sr.app_user_id = ?
                """,
                status.name(), insightId, appUserId) == 1;
    }

    @Transactional
    public void insertReport(ReportWrite report) {
        EncryptedPayload narrative = report.narrative();
        byte[] narrativeNonce = narrative.nonce();
        byte[] narrativeCiphertext = narrative.ciphertext();
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO summary_run (
                        id, app_user_id, period_start, period_end, run_status,
                        narrative_nonce, narrative_ciphertext, expires_at,
                        run_purpose, coverage_status, eligible_message_count, selected_message_count,
                        additional_message_count, read_expansion_approved, provider_type, provider_model_id,
                        provider_config_version, prompt_version, ruleset_version, scheduled_report_date
                    ) VALUES (?, ?, ?, ?, 'COMPLETED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    report.id(), report.appUserId(), Timestamp.from(report.periodStart()), Timestamp.from(report.periodEnd()),
                    narrativeNonce, narrativeCiphertext, Timestamp.from(report.expiresAt()),
                    report.purpose(), report.coverageStatus(), report.eligibleMessageCount(),
                    report.selectedMessageCount(), report.additionalMessageCount(), report.readExpansionApproved(),
                    report.provider() == null ? null : report.provider().providerType(),
                    report.provider() == null ? null : report.provider().modelId(),
                    report.provider() == null ? null : report.provider().configurationVersion(),
                    report.promptVersion(), report.rulesetVersion(), report.scheduledReportDate());
        } finally {
            wipe(narrativeNonce);
            wipe(narrativeCiphertext);
        }

        for (UUID mailboxId : report.mailboxIds()) {
            jdbcTemplate.update(
                    "INSERT INTO summary_run_mailbox (summary_run_id, app_user_id, mailbox_id) VALUES (?, ?, ?)",
                    report.id(), report.appUserId(), mailboxId);
        }
        for (ReportSourceWrite source : report.sources()) {
            jdbcTemplate.update(
                    """
                    INSERT INTO summary_run_source (summary_run_id, app_user_id, mailbox_id, message_id)
                    VALUES (?, ?, ?, ?)
                    """,
                    report.id(), report.appUserId(), source.mailboxId(), source.messageId());
        }
        for (InsightWrite insight : report.insights()) {
            EncryptedPayload encrypted = insight.content();
            byte[] nonce = encrypted.nonce();
            byte[] ciphertext = encrypted.ciphertext();
            try {
                jdbcTemplate.update(
                        """
                        INSERT INTO assistant_insight (
                            id, summary_run_id, app_user_id, insight_kind, insight_status, confidence,
                            urgency, due_at, content_nonce, content_ciphertext, expires_at
                        ) VALUES (?, ?, ?, ?, 'OPEN', ?, ?, ?, ?, ?, ?)
                        """,
                        insight.id(), report.id(), report.appUserId(), insight.kind(), insight.confidence(),
                        null, null, nonce, ciphertext, Timestamp.from(insight.expiresAt()));
            } finally {
                wipe(nonce);
                wipe(ciphertext);
            }
            for (InsightSourceWrite source : insight.sources()) {
                jdbcTemplate.update(
                        """
                        INSERT INTO assistant_insight_source (
                            insight_id, message_id, summary_run_id, app_user_id, mailbox_id
                        ) VALUES (?, ?, ?, ?, ?)
                        """,
                        insight.id(), source.messageId(), report.id(), report.appUserId(), source.mailboxId());
            }
        }
    }

    private List<UUID> findRunMailboxIds(UUID reportId) {
        return jdbcTemplate.query(
                "SELECT mailbox_id FROM summary_run_mailbox WHERE summary_run_id = ?",
                (rs, row) -> rs.getObject("mailbox_id", UUID.class),
                reportId);
    }

    private static void wipe(byte[] value) {
        if (value != null) Arrays.fill(value, (byte) 0);
    }

    public record ReportInputMessage(
            UUID id,
            UUID mailboxId,
            String accountEmail,
            UUID threadId,
            String gmailThreadId,
            String subject,
            String gmailMessageId,
            String senderEmail,
            String senderName,
            Instant sentAt,
            byte[] bodyNonce,
            byte[] bodyCiphertext,
            Instant bodyExpiresAt) {
        public void destroy() {
            if (bodyNonce != null) Arrays.fill(bodyNonce, (byte) 0);
            if (bodyCiphertext != null) Arrays.fill(bodyCiphertext, (byte) 0);
        }
    }

    public record ReportRunRow(
            UUID id,
            String status,
            Instant periodStart,
            Instant periodEnd,
            byte[] narrativeNonce,
            byte[] narrativeCiphertext,
            Instant expiresAt,
            Instant createdAt,
            String coverageStatus,
            int eligibleMessageCount,
            int selectedMessageCount,
            int additionalMessageCount,
            String providerType,
            String providerModelId,
            LocalDate scheduledReportDate) {
        public ReportCoverage coverage() {
            return new ReportCoverage(ReportModels.CoverageStatus.valueOf(coverageStatus),
                    eligibleMessageCount, selectedMessageCount, additionalMessageCount);
        }

        public SummaryResponse response(String narrative, List<MailboxScope> scope) {
            LocalDate displayedStart = scheduledReportDate == null
                    ? periodStart.atZone(java.time.ZoneOffset.UTC).toLocalDate()
                    : scheduledReportDate;
            LocalDate displayedEnd = scheduledReportDate == null
                    ? periodEnd.minusNanos(1).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                    : scheduledReportDate;
            return new SummaryResponse(id, status, createdAt,
                    displayedStart,
                    displayedEnd,
                    narrative, providerType, providerModelId, coverage(), scope);
        }

        public void destroy() {
            if (narrativeNonce != null) Arrays.fill(narrativeNonce, (byte) 0);
            if (narrativeCiphertext != null) Arrays.fill(narrativeCiphertext, (byte) 0);
        }
    }

    public record InsightRow(
            UUID id,
            String kind,
            InsightStatus status,
            double confidence,
            byte[] contentNonce,
            byte[] contentCiphertext,
            Instant expiresAt) {
        public void destroy() {
            if (contentNonce != null) Arrays.fill(contentNonce, (byte) 0);
            if (contentCiphertext != null) Arrays.fill(contentCiphertext, (byte) 0);
        }
    }

    public record ReportWrite(
            UUID id,
            UUID appUserId,
            Instant periodStart,
            Instant periodEnd,
            Instant expiresAt,
            String purpose,
            String coverageStatus,
            int eligibleMessageCount,
            int selectedMessageCount,
            int additionalMessageCount,
            boolean readExpansionApproved,
            com.east.app.ai.AIProviderClient.ProviderMetadata provider,
            String promptVersion,
            String rulesetVersion,
            LocalDate scheduledReportDate,
            List<UUID> mailboxIds,
            EncryptedPayload narrative,
            List<ReportSourceWrite> sources,
            List<InsightWrite> insights) {
        public ReportWrite {
            mailboxIds = List.copyOf(mailboxIds);
            sources = List.copyOf(sources);
            insights = List.copyOf(insights);
        }
    }

    public record ReportSourceWrite(UUID mailboxId, UUID messageId) { }
    public record InsightSourceWrite(UUID mailboxId, UUID messageId) { }
    public record InsightWrite(
            UUID id,
            String kind,
            double confidence,
            Instant expiresAt,
            EncryptedPayload content,
            List<InsightSourceWrite> sources) {
        public InsightWrite {
            sources = List.copyOf(sources);
        }
    }
}

package com.east.app.reports;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class ReportModels {

    private ReportModels() { }

    public enum CoverageStatus { COMPLETE, PARTIAL, UNKNOWN }
    public enum GenerationStatus { DISCLOSURE_REQUIRED, EXPANSION_REQUIRED, COMPLETED }
    public enum ExpansionDecision { ALLOW, DECLINE }
    public enum InsightStatus { OPEN, DISMISSED, HANDLED }

    public record GenerateReportRequest(
            LocalDate periodStart,
            LocalDate periodEnd,
            List<UUID> mailboxIds,
            boolean providerDisclosureConfirmed,
            @NotBlank @Size(max = 64) @Pattern(regexp = "[a-f0-9]{64}") String providerConfigurationVersion,
            Integer expectedEligibleMessageCount,
            Integer expectedAdditionalMessageCount,
            ExpansionDecision expandedReadDecision) {
        public GenerateReportRequest {
            mailboxIds = mailboxIds == null ? List.of() : List.copyOf(mailboxIds);
        }
    }

    public record DashboardResponse(
            boolean aiConfigured,
            boolean firstReportRecommended,
            LocalDate periodStart,
            LocalDate periodEnd,
            ReportCoverage coverage,
            SummaryResponse summary,
            List<InsightResponse> insights) {
        public DashboardResponse {
            insights = List.copyOf(insights);
        }
    }

    public record ReportCoverage(
            CoverageStatus status,
            int eligibleMessageCount,
            int selectedMessageCount,
            int additionalMessageCount) { }

    public record SummaryResponse(
            UUID id,
            String status,
            Instant generatedAt,
            LocalDate periodStart,
            LocalDate periodEnd,
            String narrative,
            String providerType,
            String providerModelId,
            ReportCoverage coverage,
            List<MailboxScope> scope) {
        public SummaryResponse {
            scope = List.copyOf(scope);
        }
    }

    public record MailboxScope(UUID mailboxId, String email, String displayName, int colorIndex) { }

    public record InsightResponse(
            UUID id,
            String kind,
            InsightStatus status,
            double confidence,
            String text,
            String urgency,
            Instant dueAt,
            String transactionType,
            BigDecimal amount,
            String currency,
            LocalDate transactionDate,
            List<ReportSource> sources) {
        public InsightResponse {
            sources = List.copyOf(sources);
        }
    }

    public record ReportSource(
            UUID mailboxId,
            UUID threadId,
            UUID messageId,
            String accountEmail,
            Instant sentAt) { }

    public record GenerateReportResponse(
            GenerationStatus status,
            int initialLimit,
            int maxAdditionalMessageCount,
            ReportCoverage coverage,
            SummaryResponse summary,
            List<InsightResponse> insights) {
        public GenerateReportResponse {
            insights = insights == null ? List.of() : List.copyOf(insights);
        }
    }

    public record UpdateInsightStatusRequest(InsightStatus status) { }

    public record DailyReportSettingsRequest(
            boolean enabled,
            LocalTime runAtLocal,
            String timezoneId,
            boolean providerDisclosureConfirmed,
            @Size(max = 64) @Pattern(regexp = "[a-f0-9]{64}") String providerConfigurationVersion) { }

    public record DailyReportSettingsResponse(
            boolean enabled,
            LocalTime runAtLocal,
            String timezoneId,
            boolean providerDisclosureRequired) { }
}

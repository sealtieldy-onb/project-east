package com.east.app.reports;

import com.east.app.ai.AIProviderClient;
import com.east.app.ai.AIProviderException;
import com.east.app.ai.AIProviderGateway;
import com.east.app.reports.DailyReportScheduleRepository.ScheduleRow;
import com.east.app.reports.ReportModels.GenerateReportResponse;
import com.east.app.reports.ReportModels.GenerationStatus;
import com.east.app.reports.ReportModels.MailboxScope;
import com.east.app.vault.VaultService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs enabled daily reports while East is open and the local key is available. */
@Component
public class DailyReportScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DailyReportScheduler.class);

    private final DailyReportScheduleRepository scheduleRepository;
    private final ReportRepository reportRepository;
    private final ReportService reportService;
    private final AIProviderGateway aiProviderClient;
    private final VaultService vaultService;
    private final Clock clock;

    public DailyReportScheduler(
            DailyReportScheduleRepository scheduleRepository,
            ReportRepository reportRepository,
            ReportService reportService,
            AIProviderGateway aiProviderClient,
            VaultService vaultService,
            Clock clock) {
        this.scheduleRepository = scheduleRepository;
        this.reportRepository = reportRepository;
        this.reportService = reportService;
        this.aiProviderClient = aiProviderClient;
        this.vaultService = vaultService;
        this.clock = clock;
    }

    @Scheduled(
            initialDelayString = "${east.reports.daily-initial-delay-ms:60000}",
            fixedDelayString = "${east.reports.daily-poll-ms:60000}")
    public void runDueDailyReports() {
        if (!vaultService.status().unlocked()) return;
        List<ScheduleRow> schedules;
        try {
            schedules = scheduleRepository.findEnabled();
        } catch (RuntimeException exception) {
            LOGGER.warn("Daily report schedules could not be read; no report content was processed.");
            return;
        }
        for (ScheduleRow schedule : schedules) {
            try {
                runSchedule(schedule);
            } catch (RuntimeException exception) {
                LOGGER.warn("A scheduled report attempt failed; report content and provider details were omitted.");
            }
        }
    }

    private void runSchedule(ScheduleRow schedule) {
        if (schedule.appUserId() == null || schedule.providerDisclosureVersion() == null) {
            if (schedule.appUserId() != null) scheduleRepository.pause(schedule.appUserId());
            return;
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(schedule.timezoneId());
        } catch (RuntimeException exception) {
            scheduleRepository.pause(schedule.appUserId());
            return;
        }
        ZonedDateTime localNow = clock.instant().atZone(zone);
        LocalDate reportDate = localNow.toLocalDate().minusDays(1);
        if (schedule.lastAttemptedReportDate() != null
                && !reportDate.isAfter(schedule.lastAttemptedReportDate())) return;

        boolean beforeRunTime = localNow.toLocalTime().isBefore(schedule.runAtLocal());
        boolean startupCatchUp = false;
        if (beforeRunTime) {
            LocalDateTime previousScheduledTime = localNow.toLocalDate().minusDays(1).atTime(schedule.runAtLocal());
            if (schedule.lastAttemptedReportDate() != null) {
                startupCatchUp = schedule.lastAttemptedReportDate().isBefore(localNow.toLocalDate().minusDays(2));
            } else if (schedule.updatedAt() != null) {
                LocalDateTime lastSettingsChange = schedule.updatedAt().atZone(zone).toLocalDateTime();
                startupCatchUp = !lastSettingsChange.isAfter(previousScheduledTime);
            }
            if (!startupCatchUp) return;
        }

        AIProviderClient.ProviderMetadata provider = aiProviderClient.providerMetadata();
        if (provider == null || !schedule.providerDisclosureVersion().equals(provider.configurationVersion())) {
            scheduleRepository.pause(schedule.appUserId());
            return;
        }

        if (reportRepository.hasScheduledReport(schedule.appUserId(), reportDate)) {
            if (scheduleRepository.claimAttempt(schedule.appUserId(), reportDate, clock.instant())) {
                scheduleRepository.recordSuccess(schedule.appUserId(), reportDate, clock.instant());
            }
            return;
        }

        List<MailboxScope> scope = reportRepository.findOwnedMailboxes(schedule.appUserId(), List.of());
        if (scope.isEmpty()) return;
        if (!scheduleRepository.claimAttempt(schedule.appUserId(), reportDate, clock.instant())) return;

        try {
            GenerateReportResponse result = reportService.generateScheduled(
                    schedule.appUserId(), scope, reportDate, zone,
                    startupCatchUp || beforeRunTime
                            || localNow.toLocalTime().isAfter(schedule.runAtLocal().plusMinutes(2))
                            ? "CATCH_UP" : "DAILY",
                    schedule.providerDisclosureVersion());
            if (result.status() == GenerationStatus.COMPLETED) {
                scheduleRepository.recordSuccess(schedule.appUserId(), reportDate, clock.instant());
            }
        } catch (AIProviderException exception) {
            if (exception.reason() == AIProviderException.Reason.DISCLOSURE_CHANGED
                    || exception.reason() == AIProviderException.Reason.NOT_CONFIGURED) {
                scheduleRepository.pause(schedule.appUserId());
            }
            throw exception;
        } catch (ReportService.ReportRequestException exception) {
            if (exception.statusCode() == 409) scheduleRepository.pause(schedule.appUserId());
            throw exception;
        }
    }
}

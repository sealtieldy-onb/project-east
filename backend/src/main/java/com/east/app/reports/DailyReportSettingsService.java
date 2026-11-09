package com.east.app.reports;

import com.east.app.ai.AIProviderClient;
import com.east.app.ai.AIProviderGateway;
import com.east.app.reports.ReportModels.DailyReportSettingsRequest;
import com.east.app.reports.ReportModels.DailyReportSettingsResponse;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DailyReportSettingsService {

    private static final LocalTime DEFAULT_RUN_TIME = LocalTime.of(8, 0);
    private static final String DEFAULT_ZONE = "UTC";

    private final DailyReportScheduleRepository scheduleRepository;
    private final AIProviderGateway aiProviderClient;

    public DailyReportSettingsService(
            DailyReportScheduleRepository scheduleRepository,
            AIProviderGateway aiProviderClient) {
        this.scheduleRepository = scheduleRepository;
        this.aiProviderClient = aiProviderClient;
    }

    @Transactional(readOnly = true)
    public DailyReportSettingsResponse get(UUID appUserId) {
        DailyReportScheduleRepository.ScheduleRow schedule = scheduleRepository.find(appUserId).orElse(null);
        AIProviderClient.ProviderMetadata current = aiProviderClient.providerMetadata();
        boolean hasSavedDisclosure = schedule != null && schedule.providerDisclosureVersion() != null;
        boolean changed = schedule != null && schedule.enabled() && (current == null
                || !hasSavedDisclosure
                || !schedule.providerDisclosureVersion().equals(current.configurationVersion()));
        boolean disclosureRequired = changed || (hasSavedDisclosure && (current == null
                || !schedule.providerDisclosureVersion().equals(current.configurationVersion())));
        return new DailyReportSettingsResponse(
                schedule != null && schedule.enabled() && !changed,
                schedule == null ? DEFAULT_RUN_TIME : schedule.runAtLocal(),
                schedule == null ? DEFAULT_ZONE : schedule.timezoneId(),
                disclosureRequired);
    }

    @Transactional
    public DailyReportSettingsResponse update(UUID appUserId, DailyReportSettingsRequest request) {
        if (request == null || request.runAtLocal() == null
                || request.timezoneId() == null || request.timezoneId().isBlank()) {
            throw ReportService.ReportRequestException.badRequest("Choose a daily report time and timezone.");
        }
        if (request.timezoneId().length() > 128) {
            throw ReportService.ReportRequestException.badRequest("The timezone identifier is invalid.");
        }
        try {
            ZoneId.of(request.timezoneId());
        } catch (RuntimeException exception) {
            throw ReportService.ReportRequestException.badRequest("The timezone identifier is invalid.");
        }

        String disclosureVersion = null;
        if (request.enabled()) {
            AIProviderClient.ProviderMetadata provider = aiProviderClient.providerMetadata();
            if (provider == null) {
                throw ReportService.ReportRequestException.conflict(
                        "Configure an AI provider and API key before enabling daily reports.");
            }
            if (!request.providerDisclosureConfirmed()) {
                throw ReportService.ReportRequestException.badRequest(
                        "Confirm the provider and email-content disclosure before enabling daily reports.");
            }
            if (request.providerConfigurationVersion() == null
                    || !request.providerConfigurationVersion().equals(provider.configurationVersion())) {
                throw ReportService.ReportRequestException.disclosureChanged();
            }
            disclosureVersion = provider.configurationVersion();
        }
        scheduleRepository.save(
                appUserId,
                request.enabled(),
                request.runAtLocal(),
                request.timezoneId(),
                disclosureVersion);
        return get(appUserId);
    }
}

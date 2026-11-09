package com.east.app.reports;

import com.east.app.auth.EastUserPrincipal;
import com.east.app.reports.ReportModels.DailyReportSettingsRequest;
import com.east.app.reports.ReportModels.DailyReportSettingsResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports/daily/settings")
public class DailyReportSettingsController {

    private final DailyReportSettingsService settingsService;

    public DailyReportSettingsController(DailyReportSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public DailyReportSettingsResponse get(@AuthenticationPrincipal EastUserPrincipal principal) {
        return settingsService.get(principal.appUserId());
    }

    @PutMapping
    public DailyReportSettingsResponse update(
            @AuthenticationPrincipal EastUserPrincipal principal,
            @Valid @RequestBody DailyReportSettingsRequest request) {
        return settingsService.update(principal.appUserId(), request);
    }
}

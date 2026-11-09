package com.east.app.reports;

import com.east.app.auth.EastUserPrincipal;
import com.east.app.reports.ReportModels.DashboardResponse;
import com.east.app.reports.ReportModels.GenerateReportRequest;
import com.east.app.reports.ReportModels.GenerateReportResponse;
import com.east.app.reports.ReportModels.UpdateInsightStatusRequest;
import java.time.LocalDate;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final ReportService reportService;

    public DashboardController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping
    public DashboardResponse getDashboard(
            @AuthenticationPrincipal EastUserPrincipal principal,
            @RequestParam(required = false) LocalDate periodStart,
            @RequestParam(required = false) LocalDate periodEnd,
            @RequestParam(required = false) UUID mailboxId) {
        return reportService.getDashboard(principal.appUserId(), periodStart, periodEnd, mailboxId);
    }

    @PostMapping("/summaries")
    public GenerateReportResponse generateReport(
            @AuthenticationPrincipal EastUserPrincipal principal,
            @Valid @RequestBody GenerateReportRequest request) {
        return reportService.generate(principal.appUserId(), request);
    }

    @PatchMapping("/insights/{insightId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateInsightStatus(
            @AuthenticationPrincipal EastUserPrincipal principal,
            @PathVariable UUID insightId,
            @RequestBody UpdateInsightStatusRequest request) {
        reportService.updateInsightStatus(principal.appUserId(), insightId,
                request == null ? null : request.status());
    }
}

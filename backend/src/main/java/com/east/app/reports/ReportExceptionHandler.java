package com.east.app.reports;

import com.east.app.ai.AIProviderException;
import com.east.app.gmail.GmailApiClient;
import com.east.app.gmail.GmailSyncService;
import com.east.app.vault.VaultService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {DashboardController.class, DailyReportSettingsController.class})
public class ReportExceptionHandler {

    @ExceptionHandler(ReportService.ReportRequestException.class)
    ResponseEntity<Map<String, String>> handleReportRequest(ReportService.ReportRequestException exception) {
        return ResponseEntity.status(exception.statusCode()).body(Map.of(
                "errorCode", exception.errorCode(),
                "message", exception.getMessage()));
    }

    @ExceptionHandler(AIProviderException.class)
    ResponseEntity<Map<String, String>> handleProvider(AIProviderException exception) {
        HttpStatus status = switch (exception.reason()) {
            case NOT_CONFIGURED, INVALID_CONFIGURATION, DISCLOSURE_CHANGED -> HttpStatus.CONFLICT;
            case VAULT_UNAVAILABLE -> HttpStatus.LOCKED;
            case PROVIDER_REJECTED, PROVIDER_UNAVAILABLE,
                    INVALID_PROVIDER_RESPONSE, INVALID_MODEL_OUTPUT -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status).body(Map.of(
                "errorCode", exception.reason().name(),
                "message", exception.getMessage()));
    }

    @ExceptionHandler(GmailApiClient.GmailApiException.class)
    ResponseEntity<Map<String, Object>> handleGmailApi(GmailApiClient.GmailApiException exception) {
        if (exception.getHttpStatus() == 401 && exception.isMailboxReauthorizationRequired()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "errorCode", "MAILBOX_AUTH_REQUIRED",
                    "message", "A connected Gmail account needs reconnection."));
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                "errorCode", exception.getHttpStatus() == 401 ? "GMAIL_AUTH_RETRY" : "GMAIL_API_ERROR",
                "message", "Gmail could not complete the report read request.",
                "gmailHttpStatus", exception.getHttpStatus(),
                "googleErrorCode", exception.getGoogleErrorCode()));
    }

    @ExceptionHandler(GmailSyncService.ReportWindowTooLargeException.class)
    ResponseEntity<Map<String, String>> handleLargeWindow() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                "errorCode", "REPORT_WINDOW_TOO_LARGE",
                "message", "The report date range contains too many messages. Narrow the date range and try again."));
    }

    @ExceptionHandler(GmailSyncService.SyncInProgressException.class)
    ResponseEntity<Map<String, String>> handleSyncInProgress() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "errorCode", "SYNC_IN_PROGRESS",
                "message", "This Gmail account is being refreshed. Try generating the report again shortly."));
    }

    @ExceptionHandler(VaultService.VaultLockedException.class)
    ResponseEntity<Map<String, String>> handleVaultLocked() {
        return ResponseEntity.status(HttpStatus.LOCKED).body(Map.of(
                "errorCode", "VAULT_LOCKED",
                "message", "Unlock East to continue."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> handleInvalidRequest() {
        return ResponseEntity.badRequest().body(Map.of(
                "errorCode", "INVALID_REQUEST",
                "message", "The report request is invalid."));
    }
}

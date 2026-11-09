package com.east.app.mailread;

import com.east.app.auth.AuthFlowException;
import com.east.app.gmail.GmailApiClient;
import com.east.app.gmail.GmailSyncService;
import com.east.app.vault.VaultService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = MailReadController.class)
public class MailReadExceptionHandler {

    @ExceptionHandler(MailReadException.class)
    ResponseEntity<Map<String, String>> handleMailRead(MailReadException exception) {
        return ResponseEntity.status(exception.status()).body(Map.of(
                "errorCode", exception.errorCode(),
                "message", exception.getMessage()));
    }

    @ExceptionHandler(GmailApiClient.GmailApiException.class)
    ResponseEntity<Map<String, Object>> handleGmailApi(GmailApiClient.GmailApiException exception) {
        if (exception.getHttpStatus() == 401 && exception.isMailboxReauthorizationRequired()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "errorCode", "MAILBOX_AUTH_REQUIRED",
                    "message", "A connected Gmail account needs reconnection."));
        }
        if (exception.getHttpStatus() == 401) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "errorCode", "GMAIL_AUTH_RETRY",
                    "message", "Gmail rejected an outdated access token. Retry; reconnect only if East asks.",
                    "gmailHttpStatus", exception.getHttpStatus(),
                    "googleErrorCode", exception.getGoogleErrorCode()));
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                "errorCode", "GMAIL_API_ERROR",
                "message", "Gmail could not complete the read request.",
                "gmailHttpStatus", exception.getHttpStatus(),
                "googleErrorCode", exception.getGoogleErrorCode()));
    }

    @ExceptionHandler(AuthFlowException.class)
    ResponseEntity<Map<String, String>> handleMailboxAuthorization(AuthFlowException exception) {
        if (exception.status() == HttpStatus.UNAUTHORIZED) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "errorCode", "MAILBOX_AUTH_REQUIRED",
                    "message", "A connected Gmail account needs reconnection."));
        }
        if (exception.status() == HttpStatus.FORBIDDEN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "errorCode", "GMAIL_AUTHORIZATION_REQUIRED",
                    "message", "Reconnect this Gmail account and grant East the requested mailbox permission."));
        }
        return ResponseEntity.status(exception.status()).body(Map.of(
                "errorCode", "GMAIL_AUTH_RETRY",
                "message", "Google could not refresh this mailbox. Try again."));
    }

    @ExceptionHandler(VaultService.VaultLockedException.class)
    ResponseEntity<Map<String, String>> handleLockedVault() {
        return ResponseEntity.status(HttpStatus.LOCKED).body(Map.of(
                "errorCode", "VAULT_LOCKED",
                "message", "Unlock East to continue."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> handleInvalidArgument() {
        return ResponseEntity.badRequest().body(Map.of(
                "errorCode", "INVALID_REQUEST",
                "message", "The conversation request is invalid."));
    }

    @ExceptionHandler(GmailSyncService.SyncInProgressException.class)
    ResponseEntity<Map<String, String>> handleSyncInProgress() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "errorCode", "SYNC_IN_PROGRESS",
                "message", "This mailbox is being refreshed. Try opening the conversation again shortly."));
    }
}

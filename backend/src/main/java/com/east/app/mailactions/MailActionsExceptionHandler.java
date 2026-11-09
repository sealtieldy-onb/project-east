package com.east.app.mailactions;

import java.util.Map;

import com.east.app.auth.AuthFlowException;
import com.east.app.gmail.GmailApiClient;
import com.east.app.vault.VaultService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = MailActionsController.class)
public class MailActionsExceptionHandler {

    @ExceptionHandler(MailActionsException.class)
    ResponseEntity<Map<String, String>> handleMailActions(MailActionsException exception) {
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
                    "message", "Gmail rejected an outdated access token. Retry; reconnect only if East asks."));
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                "errorCode", "GMAIL_API_ERROR",
                "message", "Gmail did not accept the requested action.",
                "gmailHttpStatus", exception.getHttpStatus(),
                "googleErrorCode", exception.getGoogleErrorCode()));
    }

    @ExceptionHandler(AuthFlowException.class)
    ResponseEntity<Map<String, String>> handleAuthorization(AuthFlowException exception) {
        return ResponseEntity.status(exception.status()).body(Map.of(
                "errorCode", "AUTHORIZATION_REQUIRED",
                "message", "Reconnect the mailbox and try again."));
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
                "message", "The requested Gmail action is invalid."));
    }
}

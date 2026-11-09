package com.east.app.drafts;

import java.util.Map;

import com.east.app.ai.AIProviderException;
import com.east.app.auth.AuthFlowException;
import com.east.app.gmail.GmailApiClient;
import com.east.app.vault.VaultService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.MethodArgumentNotValidException;

@RestControllerAdvice(assignableTypes = DraftController.class)
public class DraftExceptionHandler {

    @ExceptionHandler(DraftException.class)
    ResponseEntity<Map<String, String>> handleDraft(DraftException exception) {
        String code = exception.errorCode();
        if (code == null) {
            code = switch (exception.status()) {
                case BAD_REQUEST -> "INVALID_DRAFT";
                case UNAUTHORIZED -> "AUTHENTICATION_REQUIRED";
                case NOT_FOUND -> "DRAFT_NOT_FOUND";
                case CONFLICT -> "DRAFT_STATE_CONFLICT";
                default -> "DRAFT_OPERATION_FAILED";
            };
        }
        return ResponseEntity.status(exception.status()).body(Map.of(
                "errorCode", code,
                "message", exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> handleInvalidRequest() {
        return ResponseEntity.badRequest().body(Map.of(
                "errorCode", "INVALID_DRAFT",
                "message", "The draft request is invalid. Reload the draft and review its fields."));
    }

    @ExceptionHandler(GmailApiClient.GmailApiException.class)
    ResponseEntity<Map<String, String>> handleGmailFailure(GmailApiClient.GmailApiException exception) {
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
                "message", "Gmail did not confirm the operation. Check Gmail before trying again; East did not automatically retry."));
    }

    @ExceptionHandler(AuthFlowException.class)
    ResponseEntity<Map<String, String>> handleMailboxAuthorization(AuthFlowException exception) {
        return ResponseEntity.status(exception.status()).body(Map.of(
                "errorCode", "MAILBOX_AUTHORIZATION_REQUIRED",
                "message", "Reconnect the selected mailbox and try again."));
    }

    @ExceptionHandler(VaultService.VaultLockedException.class)
    ResponseEntity<Map<String, String>> handleVaultLocked() {
        return ResponseEntity.status(HttpStatus.LOCKED).body(Map.of(
                "errorCode", "VAULT_LOCKED",
                "message", "Unlock East to continue."));
    }

    @ExceptionHandler(AIProviderException.class)
    ResponseEntity<Map<String, String>> handleAIProvider(AIProviderException exception) {
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

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, String>> handleUploadTooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                "errorCode", "ATTACHMENT_TOO_LARGE",
                "message", "Choose an attachment no larger than 10 MiB."));
    }
}

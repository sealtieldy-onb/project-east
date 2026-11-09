package com.east.app.attachments;

import com.east.app.auth.AuthFlowException;
import com.east.app.auth.EastUserPrincipal;
import com.east.app.attachments.AttachmentContentService.AttachmentContent;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestController
@RequestMapping("/api/v1/threads/{threadId}/messages/{messageId}/attachments")
public class AttachmentContentController {

    private final AttachmentContentService attachmentContentService;

    public AttachmentContentController(AttachmentContentService attachmentContentService) {
        this.attachmentContentService = attachmentContentService;
    }

    @GetMapping("/{attachmentId}")
    public ResponseEntity<byte[]> getAttachment(
            @AuthenticationPrincipal EastUserPrincipal principal,
            @PathVariable UUID threadId,
            @PathVariable UUID messageId,
            @PathVariable UUID attachmentId) {
        AttachmentContent attachment = attachmentContentService.load(
                principal.appUserId(), threadId, messageId, attachmentId);
        return response(attachment, ContentDisposition.attachment()
                .filename(attachment.filename(), StandardCharsets.UTF_8)
                .build());
    }

    @GetMapping("/{attachmentId}/inline")
    public ResponseEntity<byte[]> getInlineImage(
            @AuthenticationPrincipal EastUserPrincipal principal,
            @PathVariable UUID threadId,
            @PathVariable UUID messageId,
            @PathVariable UUID attachmentId) {
        AttachmentContent image = attachmentContentService.loadInlineImage(
                principal.appUserId(), threadId, messageId, attachmentId);
        return response(image, ContentDisposition.inline()
                .filename(image.filename(), StandardCharsets.UTF_8)
                .build());
    }

    private static ResponseEntity<byte[]> response(AttachmentContent attachment, ContentDisposition disposition) {
        return ResponseEntity.ok()
                .contentType(attachment.mediaType())
                .contentLength(attachment.body().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .body(attachment.body());
    }
}

@RestControllerAdvice(assignableTypes = AttachmentContentController.class)
class AttachmentContentExceptionHandler {

    @ExceptionHandler(AttachmentContentException.class)
    ResponseEntity<Map<String, String>> handleAttachment(AttachmentContentException exception) {
        return ResponseEntity.status(exception.status()).body(Map.of(
                "errorCode", exception.errorCode(),
                "message", exception.getMessage()));
    }

    @ExceptionHandler(com.east.app.gmail.GmailApiClient.GmailApiException.class)
    ResponseEntity<Map<String, String>> handleGmail(com.east.app.gmail.GmailApiClient.GmailApiException exception) {
        boolean rejectedCurrentToken = exception.getHttpStatus() == 401
                && exception.isMailboxReauthorizationRequired();
        HttpStatus status = exception.getHttpStatus() == 401 && !rejectedCurrentToken
                ? HttpStatus.BAD_GATEWAY
                : switch (exception.getHttpStatus()) {
                    case 401 -> HttpStatus.UNAUTHORIZED;
                    case 403 -> HttpStatus.FORBIDDEN;
                    case 404 -> HttpStatus.NOT_FOUND;
                    default -> HttpStatus.BAD_GATEWAY;
                };
        String code = status == HttpStatus.NOT_FOUND ? "ATTACHMENT_NOT_FOUND"
                : rejectedCurrentToken || status == HttpStatus.FORBIDDEN
                    ? "GMAIL_AUTHORIZATION_REQUIRED"
                    : exception.getHttpStatus() == 401 ? "GMAIL_AUTH_RETRY"
                    : "GMAIL_ATTACHMENT_READ_FAILED";
        String message = status == HttpStatus.NOT_FOUND
                ? "The attachment is no longer available in Gmail. Refresh the conversation."
                : rejectedCurrentToken || status == HttpStatus.FORBIDDEN
                    ? "Reconnect this Gmail account to access the attachment."
                    : exception.getHttpStatus() == 401
                        ? "Gmail rejected an outdated access token. Retry; reconnect only if East asks."
                    : "Gmail could not load this attachment. Try again.";
        return ResponseEntity.status(status).body(Map.of("errorCode", code, "message", message));
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

    @ExceptionHandler(com.east.app.vault.VaultService.VaultLockedException.class)
    ResponseEntity<Map<String, String>> handleLockedVault() {
        return ResponseEntity.status(HttpStatus.LOCKED).body(Map.of(
                "errorCode", "VAULT_LOCKED",
                "message", "Unlock East before opening or downloading attachments."));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> handleInvalidUpstreamData() {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                "errorCode", "ATTACHMENT_READ_FAILED",
                "message", "East could not verify the attachment returned by Gmail."));
    }
}

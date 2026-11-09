package com.east.app.gmail;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

import com.east.app.auth.EastUserPrincipal;
import com.east.app.gmail.GmailSyncService.MailboxSyncResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated entry point for a user-requested refresh of one owned mailbox. */
@RestController
@RequestMapping("/api/v1/mailboxes")
public class ManualMailboxSyncController {

    private static final Pattern SAFE_FAILURE_CODE = Pattern.compile(
            "(?:gmail_http_[0-9]{3}_[A-Za-z0-9_.-]{1,64}|auth_http_[0-9]{3}|sync_exception_[A-Za-z0-9_]{1,160}|sync_failed)");

    private final GmailSyncService gmailSyncService;

    public ManualMailboxSyncController(GmailSyncService gmailSyncService) {
        this.gmailSyncService = gmailSyncService;
    }

    @PostMapping("/{mailboxId}/sync")
    public ResponseEntity<ManualSyncResponse> syncMailbox(
            @AuthenticationPrincipal EastUserPrincipal principal,
            @PathVariable UUID mailboxId) {
        MailboxSyncResult result = gmailSyncService.syncMailbox(principal.appUserId(), mailboxId);
        if (result.success()) {
            return ResponseEntity.ok(new ManualSyncResponse("SYNCED", result.syncedAt(), null));
        }

        return switch (result.errorCode()) {
            case "mailbox_not_found" -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ManualSyncResponse("NOT_FOUND", null, null));
            case "vault_locked" -> ResponseEntity.status(HttpStatus.LOCKED)
                    .body(new ManualSyncResponse("LOCKED", null, null));
            case "sync_already_running" -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ManualSyncResponse("IN_PROGRESS", null, null));
            default -> ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new ManualSyncResponse("FAILED", null, safeFailureCode(result.errorCode())));
        };
    }

    private static String safeFailureCode(String errorCode) {
        return errorCode != null && SAFE_FAILURE_CODE.matcher(errorCode).matches() ? errorCode : "sync_failed";
    }

    public record ManualSyncResponse(String status, Instant syncedAt, String failureCode) {
    }
}

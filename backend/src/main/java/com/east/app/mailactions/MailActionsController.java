package com.east.app.mailactions;

import java.util.UUID;

import com.east.app.auth.EastUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class MailActionsController {

    private final MailActionsService mailActionsService;

    public MailActionsController(MailActionsService mailActionsService) {
        this.mailActionsService = mailActionsService;
    }

    @PatchMapping("/threads/{threadId}")
    public ThreadActionOutcome patchThread(
            @AuthenticationPrincipal EastUserPrincipal principal,
            @PathVariable UUID threadId,
            @Valid @RequestBody ThreadActionRequest request) {
        mailActionsService.performThreadAction(principal, threadId, request);
        return ThreadActionOutcome.success(threadId);
    }

    @PostMapping("/threads/bulk-actions")
    public BulkThreadActionsResponse bulkActions(
            @AuthenticationPrincipal EastUserPrincipal principal,
            @Valid @RequestBody BulkThreadActionsRequest request) {
        return mailActionsService.performBulkAction(principal, request);
    }

    @PostMapping("/mailboxes/{mailboxId}/labels")
    @ResponseStatus(HttpStatus.CREATED)
    public MailLabelResponse createLabel(
            @AuthenticationPrincipal EastUserPrincipal principal,
            @PathVariable UUID mailboxId,
            @Valid @RequestBody MailLabelRequest request) {
        return mailActionsService.createLabel(principal, mailboxId, request);
    }

    @PatchMapping("/mailboxes/{mailboxId}/labels/{labelId}")
    public MailLabelResponse renameLabel(
            @AuthenticationPrincipal EastUserPrincipal principal,
            @PathVariable UUID mailboxId,
            @PathVariable UUID labelId,
            @Valid @RequestBody MailLabelRequest request) {
        return mailActionsService.renameLabel(principal, mailboxId, labelId, request);
    }

    @DeleteMapping("/mailboxes/{mailboxId}/labels/{labelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLabel(
            @AuthenticationPrincipal EastUserPrincipal principal,
            @PathVariable UUID mailboxId,
            @PathVariable UUID labelId) {
        mailActionsService.deleteLabel(principal, mailboxId, labelId);
    }
}

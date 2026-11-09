package com.east.app.drafts;

import java.util.List;
import java.util.UUID;

import com.east.app.auth.EastUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/drafts")
public class DraftController {

    private final DraftService draftService;

    public DraftController(DraftService draftService) {
        this.draftService = draftService;
    }

    @GetMapping
    public List<DraftListItem> list(@AuthenticationPrincipal EastUserPrincipal principal) {
        return draftService.list(principal);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DraftResponse create(
            @AuthenticationPrincipal EastUserPrincipal principal,
            @Valid @RequestBody DraftWriteRequest request) {
        return draftService.create(principal, request);
    }

    @GetMapping("/{draftId}")
    public DraftResponse get(
            @AuthenticationPrincipal EastUserPrincipal principal,
            @PathVariable UUID draftId) {
        return draftService.get(principal, draftId);
    }

    @GetMapping("/{draftId}/reconciliation")
    public DraftReconciliationStatus reconciliationStatus(
            @AuthenticationPrincipal EastUserPrincipal principal,
            @PathVariable UUID draftId) {
        return draftService.reconciliationStatus(principal, draftId);
    }

    @PostMapping("/{draftId}/reconciliation")
    public DraftReconciliationResponse reconcile(
            @AuthenticationPrincipal EastUserPrincipal principal,
            @PathVariable UUID draftId,
            @Valid @RequestBody DraftReconciliationRequest request) {
        return draftService.reconcile(principal, draftId, request);
    }

    @PutMapping("/{draftId}")
    public DraftResponse update(
            @AuthenticationPrincipal EastUserPrincipal principal,
            @PathVariable UUID draftId,
            @Valid @RequestBody DraftWriteRequest request) {
        return draftService.update(principal, draftId, request);
    }

    @DeleteMapping("/{draftId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal EastUserPrincipal principal,
            @PathVariable UUID draftId) {
        draftService.delete(principal, draftId);
    }

    @PostMapping(value = "/{draftId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DraftAttachmentSummary addAttachment(
            @AuthenticationPrincipal EastUserPrincipal principal,
            @PathVariable UUID draftId,
            @RequestPart("file") MultipartFile file) {
        return draftService.addAttachment(principal, draftId, file);
    }

    @DeleteMapping("/{draftId}/attachments/{attachmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAttachment(
            @AuthenticationPrincipal EastUserPrincipal principal,
            @PathVariable UUID draftId,
            @PathVariable UUID attachmentId) {
        draftService.deleteAttachment(principal, draftId, attachmentId);
    }

    @PostMapping("/{draftId}/save-to-gmail")
    public DraftSaveResponse saveToGmail(
            @AuthenticationPrincipal EastUserPrincipal principal,
            @PathVariable UUID draftId) {
        return draftService.saveToGmail(principal, draftId);
    }

    @PostMapping("/{draftId}/send")
    public DraftSendResponse send(
            @AuthenticationPrincipal EastUserPrincipal principal,
            @PathVariable UUID draftId,
            @Valid @RequestBody SendDraftRequest request) {
        return draftService.send(principal, draftId, request);
    }
}

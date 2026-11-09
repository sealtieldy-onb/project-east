package com.east.app.drafts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DraftResponse(
        UUID id,
        UUID mailboxId,
        String fromAddress,
        String kind,
        String state,
        UUID sourceThreadId,
        UUID sourceMessageId,
        List<String> to,
        List<String> cc,
        List<String> bcc,
        String subject,
        String plainTextBody,
        String richTextHtml,
        List<DraftAttachmentSummary> attachments,
        Instant createdAt,
        Instant updatedAt,
        Instant savedAt,
        String reviewFingerprint) {
    public DraftResponse {
        to = to == null ? null : List.copyOf(to);
        cc = cc == null ? null : List.copyOf(cc);
        bcc = bcc == null ? null : List.copyOf(bcc);
        attachments = List.copyOf(attachments);
    }
}

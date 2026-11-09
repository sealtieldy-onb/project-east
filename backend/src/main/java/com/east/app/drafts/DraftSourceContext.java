package com.east.app.drafts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

record DraftSourceContext(
        UUID sourceThreadId,
        UUID sourceMessageId,
        UUID mailboxId,
        String gmailThreadId,
        String gmailMessageId,
        String threadSubject,
        String senderEmail,
        String senderName,
        Instant sentAt,
        String bodyText,
        String bodyHtml,
        List<String> to,
        List<String> cc,
        List<String> replyTo,
        String inReplyTo,
        String references,
        List<DraftMimeBuilder.OutboundAttachment> forwardAttachments) {
    DraftSourceContext {
        to = List.copyOf(to);
        cc = List.copyOf(cc);
        replyTo = List.copyOf(replyTo);
        forwardAttachments = List.copyOf(forwardAttachments);
    }
}

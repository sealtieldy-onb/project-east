package com.east.app.drafts;

import java.util.List;
import java.util.UUID;

record DraftPayload(
        DraftKind kind,
        UUID sourceMessageId,
        String gmailThreadId,
        String inReplyTo,
        String references,
        List<String> to,
        List<String> cc,
        List<String> bcc,
        String subject,
        String plainTextBody,
        String richTextHtml) {
    DraftPayload {
        to = to == null ? List.of() : List.copyOf(to);
        cc = cc == null ? List.of() : List.copyOf(cc);
        bcc = bcc == null ? List.of() : List.copyOf(bcc);
    }
}

package com.east.app.drafts;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class DraftAad {

    private DraftAad() {
    }

    static byte[] payload(UUID appUserId, UUID draftId, UUID mailboxId) {
        return ("east:app-user:" + appUserId
                + ":draft:" + draftId
                + ":mailbox:" + mailboxId
                + ":payload:v1").getBytes(StandardCharsets.UTF_8);
    }

    static byte[] attachment(UUID appUserId, UUID draftId, UUID attachmentId) {
        return ("east:app-user:" + appUserId
                + ":draft:" + draftId
                + ":attachment:" + attachmentId
                + ":content:v1").getBytes(StandardCharsets.UTF_8);
    }
}

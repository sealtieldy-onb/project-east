package com.east.app.auth;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class MailboxTokenAad {

    private MailboxTokenAad() {
    }

    static byte[] refreshToken(UUID appUserId, UUID mailboxId, String googleSubject) {
        return ("east:app-user:" + appUserId
                + ":mailbox:" + mailboxId
                + ":google-subject:" + googleSubject
                + ":oauth-refresh-token:v1").getBytes(StandardCharsets.UTF_8);
    }
}

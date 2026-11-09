package com.east.app.gmail;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Shared, versioned associated-data builders for Gmail body and snippet cache payloads. */
public final class GmailCacheAad {

    private GmailCacheAad() { }

    public static byte[] message(UUID appUserId, UUID mailboxId, String gmailThreadId, String gmailMessageId) {
        return ("east:gmail-message-cache:v1:user:" + appUserId
                + ":mailbox:" + mailboxId
                + ":thread:" + gmailThreadId
                + ":message:" + gmailMessageId).getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] threadSnippet(UUID appUserId, UUID mailboxId, String gmailThreadId) {
        return ("east:gmail-thread-snippet:v1:user:" + appUserId
                + ":mailbox:" + mailboxId
                + ":thread:" + gmailThreadId).getBytes(StandardCharsets.UTF_8);
    }
}

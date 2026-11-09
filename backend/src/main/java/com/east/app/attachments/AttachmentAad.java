package com.east.app.attachments;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Versioned associated data binds cached bytes to the exact user, mailbox, message, and attachment. */
final class AttachmentAad {

    private AttachmentAad() { }

    static byte[] forAttachment(
            UUID appUserId,
            UUID mailboxId,
            String gmailThreadId,
            String gmailMessageId,
            String gmailAttachmentId) {
        return ("east:gmail-attachment-cache:v1:user:" + appUserId
                + ":mailbox:" + mailboxId
                + ":thread:" + gmailThreadId
                + ":message:" + gmailMessageId
                + ":attachment:" + gmailAttachmentId).getBytes(StandardCharsets.UTF_8);
    }
}

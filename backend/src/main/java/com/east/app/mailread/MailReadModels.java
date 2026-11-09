package com.east.app.mailread;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MailReadModels {

    private MailReadModels() { }

    public record MailboxIdentity(
            UUID id,
            String email,
            String displayName,
            String color,
            Integer colorIndex) { }

    public record GmailLabelResponse(UUID id, String name, String color) { }

    public record MailboxLabelResponse(UUID id, String name, String color, String type) { }

    public record ThreadPageResponse(
            List<ThreadSummaryResponse> items,
            String nextPageToken,
            int totalEstimate) {
        public ThreadPageResponse {
            items = List.copyOf(items);
        }
    }

    public record SenderResponse(String name, String email) { }

    public record ThreadSummaryResponse(
            UUID id,
            UUID mailboxId,
            MailboxIdentity mailbox,
            String subject,
            String snippet,
            SenderResponse sender,
            Instant latestMessageAt,
            boolean unread,
            boolean starred,
            List<GmailLabelResponse> labels,
            String category,
            int messageCount,
            Boolean attachmentsAvailable) {
        public ThreadSummaryResponse {
            labels = List.copyOf(labels);
        }
    }

    public record AttachmentMetadataResponse(
            UUID id,
            String filename,
            String mimeType,
            Long sizeBytes) { }

    public record ThreadMessageResponse(
            UUID id,
            SenderResponse sender,
            List<String> to,
            List<String> cc,
            List<String> bcc,
            String replyTo,
            String messageId,
            Instant timestamp,
            String bodyText,
            String bodyHtml,
            String bodyUnavailableReason,
            Map<String, String> headers,
            List<AttachmentMetadataResponse> attachments) {
        public ThreadMessageResponse {
            to = List.copyOf(to);
            cc = List.copyOf(cc);
            bcc = List.copyOf(bcc);
            headers = Map.copyOf(headers);
            attachments = List.copyOf(attachments);
        }
    }

    public record ThreadDetailResponse(
            UUID id,
            UUID mailboxId,
            MailboxIdentity mailbox,
            String subject,
            List<GmailLabelResponse> labels,
            List<ThreadMessageResponse> messages,
            List<AttachmentMetadataResponse> attachments) {
        public ThreadDetailResponse {
            labels = List.copyOf(labels);
            messages = List.copyOf(messages);
            attachments = List.copyOf(attachments);
        }
    }
}

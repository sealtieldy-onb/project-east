package com.east.app.drafts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class DraftGmailReviewParserSendSnapshotTest {

    @Test
    void sendSnapshotRetainsInlineAttachmentBytesWhileOrdinaryReviewDoesNot() throws Exception {
        byte[] attachmentBytes = "synthetic receipt bytes 91f6".getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(attachmentBytes);
        JsonNode resource = new ObjectMapper().readTree("""
                {
                  "id": "draft-synthetic-1",
                  "message": {
                    "id": "message-synthetic-1",
                    "payload": {
                      "mimeType": "multipart/mixed",
                      "headers": [
                        {"name": "From", "value": "sender@example.invalid"},
                        {"name": "To", "value": "recipient@example.invalid"},
                        {"name": "Subject", "value": "Synthetic receipt"}
                      ],
                      "parts": [
                        {"mimeType": "text/plain", "body": {"data": "SGVsbG8"}},
                        {"filename": "receipt.bin", "mimeType": "application/octet-stream",
                         "body": {"size": %d, "data": "%s"}}
                      ]
                    }
                  }
                }
                """.formatted(attachmentBytes.length, encoded));
        DraftGmailReviewParser parser = new DraftGmailReviewParser(new DraftHtmlSanitizer());
        UUID draftId = UUID.randomUUID();

        DraftGmailReviewParser.ParsedDraft ordinary = parser.parse(
                draftId, DraftKind.NEW, "sender@example.invalid", null, resource);
        assertNull(ordinary.reviewedAttachments().getFirst().inlineContentBytes());
        assertNotNull(ordinary.reviewedAttachments().getFirst().contentDigest());

        DraftGmailReviewParser.ParsedDraft sendSnapshot = parser.parse(
                draftId, DraftKind.NEW, "sender@example.invalid", null, resource, true);
        byte[] retained = sendSnapshot.reviewedAttachments().getFirst().inlineContentBytes();
        assertArrayEquals(attachmentBytes, retained);
        sendSnapshot.wipeInlineAttachmentBytes();
        assertArrayEquals(new byte[retained.length], retained);
        Arrays.fill(attachmentBytes, (byte) 0);
    }
}

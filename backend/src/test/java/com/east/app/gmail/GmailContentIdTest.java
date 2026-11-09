package com.east.app.gmail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GmailContentIdTest {

    @Test
    void normalizesHeaderBracketsAndPercentEncodedCidUris() {
        assertEquals("logo+blue@example.test", GmailContentId.fromHeader(" <logo+blue@example.test> "));
        assertEquals("logo+blue@example.test", GmailContentId.fromCidUri("CID:logo%2Bblue%40example.test"));
        assertEquals("logo+blue@example.test", GmailContentId.fromCidUri("cid:logo+blue%40example.test"));
    }

    @Test
    void rejectsMalformedOrUnboundedContentIds() {
        assertNull(GmailContentId.fromHeader("<bad id@example.test>"));
        assertNull(GmailContentId.fromCidUri("cid:broken%2"));
        assertNull(GmailContentId.fromHeader("a".repeat(999)));
    }

    @Test
    void metadataSyncRequestsContentIdHeadersWithoutAttachmentBytes() {
        assertTrue(GmailApiClient.MESSAGE_STRUCTURE_FIELDS.contains("filename,headers,body("));
        assertFalse(GmailApiClient.MESSAGE_STRUCTURE_FIELDS.contains(",data"));
        assertTrue(GmailApiClient.MESSAGE_PART_DATA_FIELDS.contains("filename,headers,body("));
        assertTrue(GmailApiClient.MESSAGE_PART_DATA_FIELDS.contains(",data"));
    }
}

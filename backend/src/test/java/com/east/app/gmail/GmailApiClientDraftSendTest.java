package com.east.app.gmail;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class GmailApiClientDraftSendTest {

    @Test
    void draftSendRequestCarriesTheReviewedReplacementMessageAlongsideDraftId() throws Exception {
        Map<String, Object> reviewedMessage = Map.of(
                "raw", "c3ludGhldGljLXNlbmQtY29udGVudA",
                "threadId", "synthetic-thread-1");

        Map<String, Object> request = GmailApiClient.draftSendBody("draft-synthetic-1", reviewedMessage);
        JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsBytes(request));

        assertEquals("draft-synthetic-1", json.path("id").asText());
        assertEquals("c3ludGhldGljLXNlbmQtY29udGVudA", json.path("message").path("raw").asText());
        assertEquals("synthetic-thread-1", json.path("message").path("threadId").asText());
    }
}

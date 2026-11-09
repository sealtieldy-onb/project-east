package com.east.app.auth;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MailboxSyncStatusJsonTest {

    @Test
    void summaryExposesLowercaseCamelCaseSyncStatus() throws Exception {
        MailboxSummary summary = new MailboxSummary(
                UUID.fromString("30125ec1-9c94-47fa-b3d9-014c1b4f9b0b"),
                "test@example.invalid",
                "Test mailbox",
                3,
                false,
                MailboxSyncStatus.NEEDS_REAUTHORIZATION,
                null,
                List.of("PRIMARY"));

        String json = new ObjectMapper().writeValueAsString(summary);

        assertTrue(json.contains("\"syncStatus\":\"needs_reauthorization\""));
    }
}

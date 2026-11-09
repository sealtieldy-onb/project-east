package com.east.app.gmail;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.east.app.gmail.GmailSyncRepository.BodyUnavailableReason;
import com.east.app.gmail.GmailSyncRepository.CachedMessage;
import com.east.app.gmail.GmailSyncRepository.MailboxSyncBatch;
import com.east.app.vault.EncryptedPayload;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class GmailSyncRepositoryOnDemandTest {

    @Test
    void onDemandMetadataRefreshPersistsHeadersAndLabelsWithoutReplacingBodiesOrAttachments() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        GmailSyncRepository repository = new GmailSyncRepository(jdbc);
        Instant refreshedAt = Instant.parse("2026-11-09T08:00:00Z");
        EncryptedPayload cachedBodyMarker = new EncryptedPayload(new byte[12], new byte[16]);
        CachedMessage refreshedMessage = new CachedMessage(
                "synthetic-message-id",
                "synthetic-thread-id",
                "Updated synthetic subject",
                "updated-sender@example.invalid",
                "Updated Sender",
                refreshedAt,
                refreshedAt,
                refreshedAt,
                cachedBodyMarker,
                null,
                List.of("STARRED"),
                List.of(),
                BodyUnavailableReason.ON_DEMAND,
                false,
                false);

        try {
            repository.persistOnDemand(new MailboxSyncBatch(
                    UUID.fromString("20000000-0000-4000-8000-000000000011"),
                    UUID.fromString("20000000-0000-4000-8000-000000000012"),
                    null,
                    refreshedAt,
                    List.of(),
                    List.of(refreshedMessage),
                    Set.of()));

            String messageUpsert = jdbc.statements.stream()
                    .filter(sql -> sql.contains("INSERT INTO mail_message"))
                    .findFirst()
                    .orElse(null);
            assertNotNull(messageUpsert);
            assertTrue(messageUpsert.contains("sender_email = EXCLUDED.sender_email"));
            assertTrue(messageUpsert.contains("sender_name = EXCLUDED.sender_name"));
            assertTrue(messageUpsert.contains("sent_at = EXCLUDED.sent_at"));
            assertTrue(messageUpsert.contains("THEN EXCLUDED.body_ciphertext ELSE mail_message.body_ciphertext END"));
            assertTrue(jdbc.statements.stream().anyMatch(sql -> sql.contains("mail_message_label")));
            assertFalse(jdbc.statements.stream().anyMatch(sql -> sql.contains("mail_attachment")));
        } finally {
            cachedBodyMarker.destroy();
        }
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final List<String> statements = new ArrayList<>();

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            statements.add(sql);
            if (requiredType == UUID.class) {
                return requiredType.cast(UUID.fromString("20000000-0000-4000-8000-000000000013"));
            }
            throw new AssertionError("Unexpected JdbcTemplate queryForObject type: " + requiredType.getName());
        }

        @Override
        public int update(String sql, Object... args) {
            statements.add(sql);
            return 1;
        }
    }
}

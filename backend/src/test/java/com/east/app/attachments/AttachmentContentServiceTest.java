package com.east.app.attachments;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.east.app.auth.AuthFlowException;
import com.east.app.auth.GoogleAccessTokenService;
import com.east.app.gmail.GmailApiClient;
import com.east.app.vault.EncryptedPayload;
import com.east.app.vault.VaultService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

class AttachmentContentServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void attachmentNotOwnedByCurrentUserIsRejectedBeforeTokenOrGmailAccess() {
        UUID appUserId = UUID.randomUUID();
        List<String> events = new ArrayList<>();
        RecordingAttachmentRepository repository = new RecordingAttachmentRepository(null, events, 1);
        RecordingAccessTokenService accessTokens = new RecordingAccessTokenService(events, "synthetic-token", null);
        RecordingVaultService vault = new RecordingVaultService(null, true);
        AttachmentContentService service = createService(repository, accessTokens, vault);

        AttachmentContentException exception = assertThrows(
                AttachmentContentException.class,
                () -> service.load(appUserId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));

        assertEquals(HttpStatus.NOT_FOUND, exception.status());
        assertEquals(List.of("lookup"), events);
        assertEquals(0, accessTokens.calls);
        assertEquals(0, vault.decryptCalls);
    }

    @Test
    void cachedAttachmentIsServedOnlyWhileBothCacheAndSourceBodyAreLive() {
        UUID appUserId = UUID.randomUUID();
        UUID mailboxId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        byte[] cachedBody = "synthetic cached attachment".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] plaintext = new byte[cachedBody.length + 1];
        plaintext[0] = 1;
        System.arraycopy(cachedBody, 0, plaintext, 1, cachedBody.length);
        AttachmentRepository.AttachmentAccessRecord record = record(
                appUserId,
                mailboxId,
                threadId,
                messageId,
                attachmentId,
                NOW.minusSeconds(30),
                NOW.plusSeconds(30),
                NOW.plusSeconds(60));
        List<String> events = new ArrayList<>();
        RecordingAttachmentRepository repository = new RecordingAttachmentRepository(record, events, 1);
        RecordingAccessTokenService accessTokens = new RecordingAccessTokenService(events, "synthetic-token", null);
        RecordingVaultService vault = new RecordingVaultService(plaintext, false);
        AttachmentContentService service = createService(repository, accessTokens, vault);

        AttachmentContentService.AttachmentContent content = service.load(
                appUserId, threadId, messageId, attachmentId);

        try {
            assertArrayEquals(cachedBody, content.body());
            assertEquals("receipt.pdf", content.filename());
            assertEquals(MediaType.APPLICATION_PDF, content.mediaType());
            assertEquals(List.of("lookup"), events);
            assertEquals(1, vault.decryptCalls);
            assertEquals(0, accessTokens.calls);
        } finally {
            wipe(cachedBody);
            wipe(plaintext);
            wipe(content.body());
        }
    }

    @Test
    void expiredCacheIsClearedBeforeARefreshCanReplaceIt() {
        UUID appUserId = UUID.randomUUID();
        UUID mailboxId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        Instant sourceBodyExpiry = NOW.plusSeconds(300);
        AttachmentRepository.AttachmentAccessRecord record = record(
                appUserId,
                mailboxId,
                threadId,
                messageId,
                attachmentId,
                NOW.minusSeconds(120),
                NOW,
                sourceBodyExpiry);
        List<String> events = new ArrayList<>();
        RecordingAttachmentRepository repository = new RecordingAttachmentRepository(record, events, 1);
        AuthFlowException refreshFailure = new AuthFlowException(HttpStatus.UNAUTHORIZED, "synthetic refresh stop");
        RecordingAccessTokenService accessTokens = new RecordingAccessTokenService(events, null, refreshFailure);
        RecordingVaultService vault = new RecordingVaultService(null, true);
        AttachmentContentService service = createService(repository, accessTokens, vault);

        AuthFlowException exception = assertThrows(
                AuthFlowException.class,
                () -> service.load(appUserId, threadId, messageId, attachmentId));

        assertEquals(refreshFailure, exception);
        assertEquals(List.of("lookup", "clear-expired", "access-token"), events);
        assertEquals(0, vault.decryptCalls);
        assertEquals(1, accessTokens.calls);
    }

    @Test
    void cachedBytesFetchedBeforeExpiredSourceBodyAreNotServed() {
        UUID appUserId = UUID.randomUUID();
        UUID mailboxId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        Instant sourceBodyExpiry = NOW.minusSeconds(300);
        AttachmentRepository.AttachmentAccessRecord record = record(
                appUserId,
                mailboxId,
                threadId,
                messageId,
                attachmentId,
                sourceBodyExpiry.minusSeconds(30),
                NOW.plusSeconds(600),
                sourceBodyExpiry);
        List<String> events = new ArrayList<>();
        RecordingAttachmentRepository repository = new RecordingAttachmentRepository(record, events, 1);
        AuthFlowException refreshFailure = new AuthFlowException(HttpStatus.UNAUTHORIZED, "synthetic refresh stop");
        RecordingAccessTokenService accessTokens = new RecordingAccessTokenService(events, null, refreshFailure);
        RecordingVaultService vault = new RecordingVaultService(null, true);
        AttachmentContentService service = createService(repository, accessTokens, vault);

        AuthFlowException exception = assertThrows(
                AuthFlowException.class,
                () -> service.load(appUserId, threadId, messageId, attachmentId));

        assertEquals(refreshFailure, exception);
        assertEquals(List.of("lookup", "clear-expired", "access-token"), events);
        assertEquals(0, vault.decryptCalls);
        assertEquals(1, accessTokens.calls);
    }

    private static AttachmentContentService createService(
            AttachmentRepository repository,
            GoogleAccessTokenService accessTokens,
            VaultService vault) {
        return new AttachmentContentService(
                repository,
                accessTokens,
                new GmailApiClient(RestClient.builder(), new ObjectMapper()),
                vault,
                CLOCK);
    }

    private static AttachmentRepository.AttachmentAccessRecord record(
            UUID appUserId,
            UUID mailboxId,
            UUID threadId,
            UUID messageId,
            UUID attachmentId,
            Instant fetchedAt,
            Instant expiresAt,
            Instant sourceBodyExpiresAt) {
        return new AttachmentRepository.AttachmentAccessRecord(
                appUserId,
                mailboxId,
                threadId,
                "synthetic-gmail-thread",
                messageId,
                "synthetic-gmail-message",
                attachmentId,
                "synthetic-gmail-attachment",
                "receipt.pdf",
                "application/pdf",
                11L,
                fetchedAt,
                expiresAt,
                sourceBodyExpiresAt,
                new byte[12],
                new byte[17]);
    }

    private static void wipe(byte[] bytes) {
        if (bytes != null) Arrays.fill(bytes, (byte) 0);
    }

    private static final class RecordingAttachmentRepository extends AttachmentRepository {
        private final AttachmentAccessRecord record;
        private final List<String> events;
        private final int clearResult;

        private RecordingAttachmentRepository(
                AttachmentAccessRecord record,
                List<String> events,
                int clearResult) {
            super(null);
            this.record = record;
            this.events = events;
            this.clearResult = clearResult;
        }

        @Override
        Optional<AttachmentAccessRecord> findOwnedAttachment(
                UUID appUserId,
                UUID threadId,
                UUID messageId,
                UUID attachmentId) {
            events.add("lookup");
            return Optional.ofNullable(record);
        }

        @Override
        int clearExpiredContent(AttachmentAccessRecord attachment, Instant now) {
            events.add("clear-expired");
            return clearResult;
        }
    }

    private static final class RecordingAccessTokenService extends GoogleAccessTokenService {
        private final List<String> events;
        private final String token;
        private final RuntimeException failure;
        private int calls;

        private RecordingAccessTokenService(List<String> events, String token, RuntimeException failure) {
            super(null, null, null, CLOCK);
            this.events = events;
            this.token = token;
            this.failure = failure;
        }

        @Override
        public synchronized String getValidAccessToken(UUID appUserId, UUID mailboxId) {
            calls++;
            events.add("access-token");
            if (failure != null) throw failure;
            return token;
        }
    }

    private static final class RecordingVaultService extends VaultService {
        private final byte[] plaintext;
        private final boolean rejectDecrypt;
        private int decryptCalls;

        private RecordingVaultService(byte[] plaintext, boolean rejectDecrypt) {
            super(null, null, null, null);
            this.plaintext = plaintext == null ? null : Arrays.copyOf(plaintext, plaintext.length);
            this.rejectDecrypt = rejectDecrypt;
        }

        @Override
        public synchronized void requireUnlocked() { }

        @Override
        public synchronized byte[] decryptLocal(EncryptedPayload payload, byte[] associatedData) {
            decryptCalls++;
            if (rejectDecrypt) throw new AssertionError("Expired or unowned cache must not be decrypted");
            return Arrays.copyOf(plaintext, plaintext.length);
        }
    }
}

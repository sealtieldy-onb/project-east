package com.east.app.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.east.app.auth.GoogleTokenClient.GoogleTokenResponse;
import com.east.app.vault.EncryptedPayload;
import com.east.app.vault.VaultService;
import com.east.app.vault.VaultStatusResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class GoogleAccessTokenServiceReauthorizationTest {

    @Test
    void late401ForReplacedTokenDoesNotMarkMailboxForReauthorization() throws ReflectiveOperationException {
        UUID appUserId = UUID.fromString("30125ec1-9c94-47fa-b3d9-014c1b4f9b0b");
        UUID mailboxId = UUID.fromString("1d7a2b14-3c7f-44af-9b5e-213597d7a7c2");
        TestMailboxRepository repository = new TestMailboxRepository(appUserId, mailboxId);
        TestTokenClient tokenClient = new TestTokenClient();
        GoogleAccessTokenService accessTokens = new GoogleAccessTokenService(
                repository,
                new TestVault(),
                tokenClient,
                Clock.fixed(Instant.parse("2026-11-09T08:00:00Z"), java.time.ZoneOffset.UTC));

        String rejectedToken = accessTokens.getValidAccessToken(appUserId, mailboxId);
        accessTokens.invalidate(mailboxId); // Reconnect replaces the old token cache entry.
        String currentToken = accessTokens.getValidAccessToken(appUserId, mailboxId);

        assertEquals("synthetic-access-token", rejectedToken);
        assertEquals("synthetic-access-token-new", currentToken);
        assertFalse(accessTokens.markReauthorizationRequiredIfCurrentAccessToken(
                appUserId, mailboxId, rejectedToken));
        assertFalse(repository.reauthorizationRequired);
        assertEquals(MailboxSyncStatus.READY, repository.syncStatus);
        assertTrue(hasCachedToken(accessTokens, mailboxId));
        assertEquals(currentToken, accessTokens.getValidAccessToken(appUserId, mailboxId));
    }

    @Test
    void currentToken401MarksMailboxForReauthorization() throws ReflectiveOperationException {
        UUID appUserId = UUID.fromString("30125ec1-9c94-47fa-b3d9-014c1b4f9b0b");
        UUID mailboxId = UUID.fromString("1d7a2b14-3c7f-44af-9b5e-213597d7a7c2");
        TestMailboxRepository repository = new TestMailboxRepository(appUserId, mailboxId);
        GoogleAccessTokenService accessTokens = new GoogleAccessTokenService(
                repository,
                new TestVault(),
                new TestTokenClient(),
                Clock.fixed(Instant.parse("2026-11-09T08:00:00Z"), java.time.ZoneOffset.UTC));
        String currentToken = accessTokens.getValidAccessToken(appUserId, mailboxId);

        assertTrue(accessTokens.markReauthorizationRequiredIfCurrentAccessToken(
                appUserId, mailboxId, currentToken));

        assertTrue(repository.reauthorizationRequired);
        assertEquals(MailboxSyncStatus.NEEDS_REAUTHORIZATION, repository.syncStatus);
        assertFalse(hasCachedToken(accessTokens, mailboxId));
    }

    @Test
    void reauthorizationInvalidatesCachedTokenAndPersistsNeedsState() throws ReflectiveOperationException {
        UUID appUserId = UUID.fromString("30125ec1-9c94-47fa-b3d9-014c1b4f9b0b");
        UUID mailboxId = UUID.fromString("1d7a2b14-3c7f-44af-9b5e-213597d7a7c2");
        TestMailboxRepository repository = new TestMailboxRepository(appUserId, mailboxId);
        TestTokenClient tokenClient = new TestTokenClient();
        GoogleAccessTokenService accessTokens = new GoogleAccessTokenService(
                repository,
                new TestVault(),
                tokenClient,
                Clock.fixed(Instant.parse("2026-11-09T08:00:00Z"), java.time.ZoneOffset.UTC));

        assertEquals("synthetic-access-token", accessTokens.getValidAccessToken(appUserId, mailboxId));
        assertEquals("synthetic-access-token", accessTokens.getValidAccessToken(appUserId, mailboxId));
        assertEquals(1, tokenClient.refreshCalls);
        assertTrue(hasCachedToken(accessTokens, mailboxId));

        accessTokens.markReauthorizationRequired(appUserId, mailboxId);

        assertTrue(repository.reauthorizationRequired);
        assertEquals(MailboxSyncStatus.NEEDS_REAUTHORIZATION, repository.syncStatus);
        assertFalse(hasCachedToken(accessTokens, mailboxId));
        AuthFlowException exception = assertThrows(
                AuthFlowException.class,
                () -> accessTokens.getValidAccessToken(appUserId, mailboxId));
        assertEquals(401, exception.status().value());
        assertEquals(1, tokenClient.refreshCalls);
    }

    private static boolean hasCachedToken(GoogleAccessTokenService service, UUID mailboxId)
            throws ReflectiveOperationException {
        Field cacheField = GoogleAccessTokenService.class.getDeclaredField("accessTokenCache");
        cacheField.setAccessible(true);
        return ((Map<?, ?>) cacheField.get(service)).containsKey(mailboxId);
    }

    private static final class TestMailboxRepository extends MailboxRepository {
        private final UUID appUserId;
        private final UUID mailboxId;
        private boolean reauthorizationRequired;
        private MailboxSyncStatus syncStatus = MailboxSyncStatus.READY;

        private TestMailboxRepository(UUID appUserId, UUID mailboxId) {
            super(null);
            this.appUserId = appUserId;
            this.mailboxId = mailboxId;
        }

        @Override
        public Optional<ConnectedMailbox> findOwned(UUID userId, UUID id) {
            if (!appUserId.equals(userId) || !mailboxId.equals(id)) return Optional.empty();
            Instant now = Instant.parse("2026-11-09T08:00:00Z");
            return Optional.of(new ConnectedMailbox(
                    mailboxId,
                    appUserId,
                    "synthetic-google-subject",
                    "test@example.invalid",
                    "Test mailbox",
                    4,
                    reauthorizationRequired,
                    syncStatus,
                    List.of(GoogleOAuthService.GMAIL_MODIFY_SCOPE),
                    new byte[12],
                    new byte[17],
                    now,
                    now,
                    now));
        }

        @Override
        public void markReauthorizationRequired(UUID userId, UUID id) {
            if (appUserId.equals(userId) && mailboxId.equals(id)) {
                reauthorizationRequired = true;
                syncStatus = MailboxSyncStatus.NEEDS_REAUTHORIZATION;
            }
        }
    }

    private static final class TestTokenClient extends GoogleTokenClient {
        private int refreshCalls;

        private TestTokenClient() {
            super(RestClient.builder(), null);
        }

        @Override
        GoogleTokenResponse refreshAccessToken(String refreshToken) {
            refreshCalls += 1;
            return new GoogleTokenResponse(
                    refreshCalls == 1 ? "synthetic-access-token" : "synthetic-access-token-new",
                    3_600L,
                    null,
                    null,
                    null,
                    "Bearer");
        }
    }

    private static final class TestVault extends VaultService {
        private TestVault() {
            super(null, null, null, null);
        }

        @Override
        public synchronized VaultStatusResponse status() {
            return new VaultStatusResponse(true, true);
        }

        @Override
        public synchronized void requireUnlocked() { }

        @Override
        public synchronized byte[] decryptLocal(EncryptedPayload payload, byte[] associatedData) {
            return "synthetic-refresh-token".getBytes(StandardCharsets.UTF_8);
        }
    }
}

package com.east.app.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import com.east.app.vault.EncryptedPayload;
import com.east.app.vault.LocalVaultKeyStore;
import com.east.app.vault.VaultCryptoService;
import com.east.app.vault.VaultRecord;
import com.east.app.vault.VaultRepository;
import com.east.app.vault.VaultService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DuplicateKeyException;

class MailboxTokenEncryptionTest {

    @TempDir
    Path tempDir;

    @Test
    void refreshTokenIsEncryptedAndBoundToItsMailboxBeforePersistence() {
        UUID appUserId = UUID.randomUUID();
        String subject = "synthetic-google-subject";
        GoogleIdentity identity = new GoogleIdentity(
                subject,
                "test@example.invalid",
                "Test mailbox",
                Set.of(GoogleOAuthService.GMAIL_MODIFY_SCOPE));
        String syntheticRefreshToken = "test-only-refresh-token-84b2";

        MemoryVaultRepository vaultRepository = new MemoryVaultRepository();
        LocalVaultKeyStore keyStore = new LocalVaultKeyStore(tempDir.resolve("east").resolve("vault.key").toString());
        VaultService vault = new VaultService(vaultRepository, new VaultCryptoService(), null, keyStore);
        vault.initializeForLocalUse();

        CapturingMailboxRepository mailboxRepository = new CapturingMailboxRepository();
        GoogleAccessTokenService accessTokens = new GoogleAccessTokenService(
                mailboxRepository, vault, null, Clock.systemUTC());
        MailboxService mailboxes = new MailboxService(mailboxRepository, vault, accessTokens);
        byte[] expectedPlaintext = syntheticRefreshToken.getBytes(StandardCharsets.UTF_8);
        EncryptedPayload savedPayload = null;
        byte[] decrypted = null;

        try {
            MailboxSummary result = mailboxes.connect(appUserId, identity, syntheticRefreshToken);
            assertEquals("test@example.invalid", result.email());
            assertEquals(17, result.colorIndex());
            assertEquals(MailboxSyncStatus.PENDING, result.syncStatus());
            assertNotNull(mailboxRepository.persistedNonce);
            assertNotNull(mailboxRepository.persistedCiphertext);
            assertEquals(12, mailboxRepository.persistedNonce.length);
            assertEquals(expectedPlaintext.length + 16, mailboxRepository.persistedCiphertext.length);
            assertFalse(contains(mailboxRepository.persistedCiphertext, expectedPlaintext));

            savedPayload = new EncryptedPayload(
                    mailboxRepository.persistedNonce,
                    mailboxRepository.persistedCiphertext);
            byte[] aad = MailboxTokenAad.refreshToken(appUserId, result.id(), subject);
            try {
                decrypted = vault.decryptLocal(savedPayload, aad);
            } finally {
                Arrays.fill(aad, (byte) 0);
            }
            assertArrayEquals(expectedPlaintext, decrypted);
            EncryptedPayload payloadForWrongAad = savedPayload;
            byte[] wrongAad = MailboxTokenAad.refreshToken(appUserId, result.id(), "different-subject");
            try {
                assertThrows(
                        VaultCryptoService.InvalidVaultCiphertextException.class,
                        () -> vault.decryptLocal(payloadForWrongAad, wrongAad));
            } finally {
                Arrays.fill(wrongAad, (byte) 0);
            }
        } finally {
            if (savedPayload != null) savedPayload.destroy();
            if (decrypted != null) Arrays.fill(decrypted, (byte) 0);
            Arrays.fill(expectedPlaintext, (byte) 0);
            mailboxRepository.clearCapturedToken();
        }
    }

    private static boolean contains(byte[] value, byte[] sequence) {
        if (sequence.length == 0 || sequence.length > value.length) return false;
        for (int start = 0; start <= value.length - sequence.length; start++) {
            int offset = 0;
            while (offset < sequence.length && value[start + offset] == sequence[offset]) offset++;
            if (offset == sequence.length) return true;
        }
        return false;
    }

    private static final class CapturingMailboxRepository extends MailboxRepository {
        private UUID id;
        private UUID appUserId;
        private String subject;
        private String email;
        private String displayName;
        private int colorIndex;
        private MailboxSyncStatus syncStatus = MailboxSyncStatus.PENDING;
        private List<String> scopes = List.of();
        private byte[] persistedNonce;
        private byte[] persistedCiphertext;

        private CapturingMailboxRepository() {
            super(null);
        }

        @Override
        public void lockAppUser(UUID userId) { }

        @Override
        public Optional<ConnectedMailbox> findByOwnerAndGoogleSubject(UUID userId, String googleSubject) {
            if (id == null || !appUserId.equals(userId) || !subject.equals(googleSubject)) return Optional.empty();
            Instant now = Instant.EPOCH;
            return Optional.of(new ConnectedMailbox(
                    id,
                    appUserId,
                    subject,
                    email,
                    displayName,
                    colorIndex,
                    false,
                    syncStatus,
                    scopes,
                    persistedNonce,
                    persistedCiphertext,
                    null,
                    now,
                    now));
        }

        @Override
        public OptionalInt nextAvailableColorIndex(UUID userId, String googleSubject) {
            return OptionalInt.of(17);
        }

        @Override
        public void upsertMailbox(
                UUID mailboxId,
                UUID userId,
                String googleSubject,
                String mailboxEmail,
                String mailboxDisplayName,
                int mailboxColorIndex,
                List<String> grantedScopes,
                byte[] refreshTokenNonce,
                byte[] refreshTokenCiphertext) {
            this.id = mailboxId;
            this.appUserId = userId;
            this.subject = googleSubject;
            this.email = mailboxEmail;
            this.displayName = mailboxDisplayName;
            this.colorIndex = mailboxColorIndex;
            this.syncStatus = MailboxSyncStatus.PENDING;
            this.scopes = List.copyOf(grantedScopes);
            this.persistedNonce = refreshTokenNonce == null ? null : Arrays.copyOf(refreshTokenNonce, refreshTokenNonce.length);
            this.persistedCiphertext = refreshTokenCiphertext == null
                    ? null
                    : Arrays.copyOf(refreshTokenCiphertext, refreshTokenCiphertext.length);
        }

        private void clearCapturedToken() {
            if (persistedNonce != null) Arrays.fill(persistedNonce, (byte) 0);
            if (persistedCiphertext != null) Arrays.fill(persistedCiphertext, (byte) 0);
        }
    }

    private static final class MemoryVaultRepository extends VaultRepository {
        private VaultRecord stored;

        private MemoryVaultRepository() {
            super(null);
        }

        @Override
        public synchronized Optional<VaultRecord> find() {
            if (stored == null) return Optional.empty();
            return Optional.of(new VaultRecord(stored.salt(), stored.encryptedVerificationMarker()));
        }

        @Override
        public synchronized boolean isInitialized() {
            return stored != null;
        }

        @Override
        public synchronized void create(byte[] salt, byte[] encryptedVerificationMarker) {
            if (stored != null) throw new DuplicateKeyException("Test vault already exists");
            stored = new VaultRecord(salt, encryptedVerificationMarker);
        }
    }
}

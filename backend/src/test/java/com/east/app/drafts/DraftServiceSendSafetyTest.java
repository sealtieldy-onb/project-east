package com.east.app.drafts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.east.app.auth.EastUserPrincipal;
import com.east.app.auth.GoogleAccessTokenService;
import com.east.app.vault.LocalVaultKeyStore;
import com.east.app.vault.VaultCryptoService;
import com.east.app.vault.VaultRecord;
import com.east.app.vault.VaultRepository;
import com.east.app.vault.VaultService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class DraftServiceSendSafetyTest {

    private static final Instant NOW = Instant.parse("2026-11-09T00:00:00Z");
    private static final String REVIEW_FINGERPRINT = "A".repeat(43);

    @TempDir
    Path tempDir;

    @Test
    void staleReviewTimestampStopsSendBeforeMailboxOrGmailAccess() {
        UUID appUserId = UUID.randomUUID();
        UUID mailboxId = UUID.randomUUID();
        UUID draftId = UUID.randomUUID();
        InMemoryDraftRepository repository = new InMemoryDraftRepository(
                draftId, appUserId, mailboxId, "LOCAL", NOW, null, null);
        RecordingAccessTokenService accessTokens = new RecordingAccessTokenService();
        DraftService service = createService(repository, accessTokens, createVault("stale-review"));

        DraftException exception = assertThrows(
                DraftException.class,
                () -> service.send(
                        principal(appUserId),
                        draftId,
                        new SendDraftRequest(true, NOW.minusSeconds(1), REVIEW_FINGERPRINT)));

        assertEquals(HttpStatus.CONFLICT, exception.status());
        assertEquals(0, accessTokens.calls);
        assertEquals(0, repository.mailboxEmailCalls);
        assertEquals(0, repository.pendingTransitionCalls);
    }

    @Test
    void sendPendingDraftCannotBeRetriedAndReportsReconciliationState() {
        UUID appUserId = UUID.randomUUID();
        UUID mailboxId = UUID.randomUUID();
        UUID draftId = UUID.randomUUID();
        Instant sideEffectStartedAt = NOW.minusSeconds(30);
        InMemoryDraftRepository repository = new InMemoryDraftRepository(
                draftId, appUserId, mailboxId, "SEND_PENDING", NOW, "SEND", sideEffectStartedAt);
        RecordingAccessTokenService accessTokens = new RecordingAccessTokenService();
        DraftService service = createService(repository, accessTokens, createVault("pending-send"));

        DraftException exception = assertThrows(
                DraftException.class,
                () -> service.send(
                        principal(appUserId),
                        draftId,
                        new SendDraftRequest(true, NOW, REVIEW_FINGERPRINT)));

        assertEquals(HttpStatus.CONFLICT, exception.status());
        assertEquals(0, accessTokens.calls);
        assertEquals(0, repository.mailboxEmailCalls);
        assertEquals(0, repository.pendingTransitionCalls);

        DraftReconciliationStatus status = service.reconciliationStatus(principal(appUserId), draftId);
        assertEquals(draftId, status.draftId());
        assertTrue(status.required());
        assertFalse(status.inProgress());
        assertEquals("SEND", status.operation());
        assertEquals("SEND_PENDING", status.state());
        assertEquals("LOCAL", status.previousState());
        assertEquals(sideEffectStartedAt, status.startedAt());
    }

    private VaultService createVault(String directory) {
        VaultService vault = new VaultService(
                new InMemoryVaultRepository(),
                new VaultCryptoService(),
                null,
                new LocalVaultKeyStore(tempDir.resolve(directory).resolve("vault.key").toString()));
        vault.initializeForLocalUse();
        return vault;
    }

    private static DraftService createService(
            DraftRepository repository,
            GoogleAccessTokenService accessTokens,
            VaultService vault) {
        return new DraftService(
                repository,
                vault,
                accessTokens,
                null,
                new ObjectMapper(),
                new DraftHtmlSanitizer(),
                null,
                null,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new NoopTransactionManager());
    }

    private static EastUserPrincipal principal(UUID appUserId) {
        return new EastUserPrincipal(appUserId, "synthetic-google-subject", "test@example.invalid", "Test user");
    }

    private static final class InMemoryDraftRepository extends DraftRepository {
        private final DraftRow row;
        private int mailboxEmailCalls;
        private int pendingTransitionCalls;

        private InMemoryDraftRepository(
                UUID draftId,
                UUID appUserId,
                UUID mailboxId,
                String state,
                Instant updatedAt,
                String sideEffectOperation,
                Instant sideEffectStartedAt) {
            super(null);
            row = new DraftRow(
                    draftId,
                    appUserId,
                    mailboxId,
                    null,
                    DraftKind.NEW.name(),
                    state,
                    null,
                    null,
                    null,
                    NOW.minusSeconds(60),
                    updatedAt,
                    null,
                    sideEffectOperation,
                    sideEffectStartedAt);
        }

        @Override
        Optional<DraftRow> findOwned(UUID ownerId, UUID draftId, boolean lockForUpdate) {
            if (!row.appUserId().equals(ownerId) || !row.id().equals(draftId)) {
                return Optional.empty();
            }
            return Optional.of(new DraftRow(
                    row.id(),
                    row.appUserId(),
                    row.mailboxId(),
                    row.sourceThreadId(),
                    row.kind(),
                    row.state(),
                    row.gmailDraftId(),
                    row.payloadNonce(),
                    row.payloadCiphertext(),
                    row.createdAt(),
                    row.updatedAt(),
                    row.savedAt(),
                    row.sideEffectOperation(),
                    row.sideEffectStartedAt()));
        }

        @Override
        Optional<String> ownedMailboxEmail(UUID ownerId, UUID mailboxId) {
            mailboxEmailCalls++;
            return Optional.of("test@example.invalid");
        }

        @Override
        int markSideEffectPending(UUID ownerId, UUID draftId, String fromState, String operation) {
            pendingTransitionCalls++;
            return 1;
        }
    }

    private static final class RecordingAccessTokenService extends GoogleAccessTokenService {
        private int calls;

        private RecordingAccessTokenService() {
            super(null, null, null, Clock.fixed(NOW, ZoneOffset.UTC));
        }

        @Override
        public synchronized String getValidAccessToken(UUID appUserId, UUID mailboxId) {
            calls++;
            return "synthetic-access-token";
        }
    }

    private static final class InMemoryVaultRepository extends VaultRepository {
        private VaultRecord record;

        private InMemoryVaultRepository() {
            super(null);
        }

        @Override
        public synchronized Optional<VaultRecord> find() {
            return record == null
                    ? Optional.empty()
                    : Optional.of(new VaultRecord(record.salt(), record.encryptedVerificationMarker()));
        }

        @Override
        public synchronized boolean isInitialized() {
            return record != null;
        }

        @Override
        public synchronized void create(byte[] salt, byte[] encryptedVerificationMarker) {
            if (record != null) throw new DuplicateKeyException("Test vault already exists");
            record = new VaultRecord(salt, encryptedVerificationMarker);
        }
    }

    private static final class NoopTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) { }

        @Override
        public void rollback(TransactionStatus status) { }
    }
}

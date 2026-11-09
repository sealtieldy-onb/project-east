package com.east.app.drafts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

class DraftServiceReconciliationTest {

    private static final Instant NOW = Instant.parse("2026-11-09T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void unknownLocalSendOutcomeCanBeResolvedOnlyAfterConfirmationAndNoGmailCall() {
        UUID appUserId = UUID.randomUUID();
        UUID mailboxId = UUID.randomUUID();
        UUID draftId = UUID.randomUUID();
        InMemoryDraftRepository repository = pendingSendRepository(draftId, appUserId, mailboxId);
        RecordingAccessTokenService accessTokens = new RecordingAccessTokenService();
        DraftService service = createService(repository, accessTokens, createVault("send-completed"));

        DraftException unconfirmed = assertThrows(
                DraftException.class,
                () -> service.reconcile(
                        principal(appUserId),
                        draftId,
                        new DraftReconciliationRequest(false, "CONFIRMED_COMPLETED", null)));
        assertEquals(HttpStatus.BAD_REQUEST, unconfirmed.status());
        assertEquals(0, repository.findOwnedCalls);
        assertEquals(0, repository.deletePendingSendCalls);

        DraftReconciliationResponse result = service.reconcile(
                principal(appUserId),
                draftId,
                new DraftReconciliationRequest(true, "CONFIRMED_COMPLETED", null));

        assertEquals(draftId, result.draftId());
        assertEquals("SEND", result.operation());
        assertEquals("SENT", result.state());
        assertEquals(NOW, result.resolvedAt());
        assertEquals(1, repository.deletePendingSendCalls);
        assertEquals(0, repository.restorePendingSideEffectCalls);
        assertEquals(0, repository.mailboxEmailCalls);
        assertEquals(0, accessTokens.calls);
        assertNull(repository.current);
        DraftException removed = assertThrows(
                DraftException.class,
                () -> service.reconciliationStatus(principal(appUserId), draftId));
        assertEquals(HttpStatus.NOT_FOUND, removed.status());
    }

    @Test
    void explicitlyUnsentLocalOperationRestoresTheDraftWithoutGmailAccess() {
        UUID appUserId = UUID.randomUUID();
        UUID mailboxId = UUID.randomUUID();
        UUID draftId = UUID.randomUUID();
        InMemoryDraftRepository repository = pendingSendRepository(draftId, appUserId, mailboxId);
        RecordingAccessTokenService accessTokens = new RecordingAccessTokenService();
        DraftService service = createService(repository, accessTokens, createVault("send-not-completed"));

        DraftReconciliationResponse result = service.reconcile(
                principal(appUserId),
                draftId,
                new DraftReconciliationRequest(true, "CONFIRMED_NOT_COMPLETED", null));

        assertEquals(draftId, result.draftId());
        assertEquals("SEND", result.operation());
        assertEquals("LOCAL", result.state());
        assertEquals(NOW, result.resolvedAt());
        assertEquals(0, repository.deletePendingSendCalls);
        assertEquals(1, repository.restorePendingSideEffectCalls);
        assertEquals(0, repository.mailboxEmailCalls);
        assertEquals(0, accessTokens.calls);

        DraftReconciliationStatus status = service.reconciliationStatus(principal(appUserId), draftId);
        assertFalse(status.required());
        assertFalse(status.inProgress());
        assertEquals("LOCAL", status.state());
        assertNull(status.operation());
        assertNull(status.previousState());
        assertNull(status.startedAt());
    }

    private InMemoryDraftRepository pendingSendRepository(UUID draftId, UUID appUserId, UUID mailboxId) {
        return new InMemoryDraftRepository(
                draftId,
                appUserId,
                mailboxId,
                "SEND_PENDING",
                "SEND",
                NOW.minusSeconds(30));
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
        private final UUID appUserId;
        private DraftRow current;
        private int findOwnedCalls;
        private int deletePendingSendCalls;
        private int restorePendingSideEffectCalls;
        private int mailboxEmailCalls;

        private InMemoryDraftRepository(
                UUID draftId,
                UUID appUserId,
                UUID mailboxId,
                String state,
                String sideEffectOperation,
                Instant sideEffectStartedAt) {
            super(null);
            this.appUserId = appUserId;
            current = new DraftRow(
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
                    NOW.minusSeconds(30),
                    null,
                    sideEffectOperation,
                    sideEffectStartedAt);
        }

        @Override
        Optional<DraftRow> findOwned(UUID ownerId, UUID draftId, boolean lockForUpdate) {
            findOwnedCalls++;
            if (current == null || !appUserId.equals(ownerId) || !current.id().equals(draftId)) {
                return Optional.empty();
            }
            return Optional.of(copy(current));
        }

        @Override
        Optional<String> ownedMailboxEmail(UUID ownerId, UUID mailboxId) {
            mailboxEmailCalls++;
            return Optional.of("test@example.invalid");
        }

        @Override
        int restorePendingSideEffect(UUID ownerId, UUID draftId, String operation) {
            restorePendingSideEffectCalls++;
            if (current == null
                    || !appUserId.equals(ownerId)
                    || !current.id().equals(draftId)
                    || !current.state().endsWith("_PENDING")
                    || !operation.equals(current.sideEffectOperation())) {
                return 0;
            }
            current.destroyPayloadBuffers();
            current = new DraftRow(
                    current.id(),
                    current.appUserId(),
                    current.mailboxId(),
                    current.sourceThreadId(),
                    current.kind(),
                    "LOCAL",
                    current.gmailDraftId(),
                    null,
                    null,
                    current.createdAt(),
                    NOW,
                    current.savedAt(),
                    null,
                    null);
            return 1;
        }

        @Override
        boolean deletePendingSend(UUID ownerId, UUID draftId) {
            deletePendingSendCalls++;
            if (current == null
                    || !appUserId.equals(ownerId)
                    || !current.id().equals(draftId)
                    || !"SEND_PENDING".equals(current.state())
                    || !"SEND".equals(current.sideEffectOperation())) {
                return false;
            }
            current.destroyPayloadBuffers();
            current = null;
            return true;
        }

        private static DraftRow copy(DraftRow row) {
            return new DraftRow(
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
                    row.sideEffectStartedAt());
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

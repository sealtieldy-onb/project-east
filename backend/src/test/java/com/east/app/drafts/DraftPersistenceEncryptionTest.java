package com.east.app.drafts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.east.app.auth.EastUserPrincipal;
import com.east.app.vault.EncryptedPayload;
import com.east.app.vault.LocalVaultKeyStore;
import com.east.app.vault.VaultCryptoService;
import com.east.app.vault.VaultRecord;
import com.east.app.vault.VaultRepository;
import com.east.app.vault.VaultService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.multipart.MultipartFile;

class DraftPersistenceEncryptionTest {

    @TempDir
    Path tempDir;

    @Test
    void localDraftPayloadIsEncryptedAndBoundToItsUserDraftAndMailboxBeforePersistence() throws Exception {
        UUID appUserId = UUID.randomUUID();
        UUID mailboxId = UUID.randomUUID();
        String syntheticSubject = "Synthetic draft subject 3c21";
        String syntheticBody = "Synthetic draft body 84b2";
        MemoryVaultRepository vaultRepository = new MemoryVaultRepository();
        VaultService vault = createVault(vaultRepository, "draft-payload");
        CapturingDraftRepository repository = new CapturingDraftRepository(appUserId, mailboxId);
        DraftService service = createDraftService(repository, vault);
        EastUserPrincipal principal = principal(appUserId);
        DraftResponse response = null;
        EncryptedPayload encrypted = null;
        byte[] expectedBody = syntheticBody.getBytes(StandardCharsets.UTF_8);
        byte[] expectedSubject = syntheticSubject.getBytes(StandardCharsets.UTF_8);
        byte[] aad = null;
        byte[] plaintext = null;

        try {
            response = service.create(principal, new DraftWriteRequest(
                    mailboxId,
                    DraftKind.NEW,
                    null,
                    null,
                    List.of("recipient@example.invalid"),
                    List.of(),
                    List.of(),
                    syntheticSubject,
                    syntheticBody,
                    null));

            assertEquals(syntheticSubject, response.subject());
            assertEquals(syntheticBody, response.plainTextBody());
            assertEquals(12, repository.payloadNonce.length);
            assertTrue(repository.payloadCiphertext.length > expectedBody.length + 16);
            assertFalse(contains(repository.payloadCiphertext, expectedBody));
            assertFalse(contains(repository.payloadCiphertext, expectedSubject));

            encrypted = new EncryptedPayload(repository.payloadNonce, repository.payloadCiphertext);
            aad = DraftAad.payload(appUserId, response.id(), mailboxId);
            plaintext = vault.decryptLocal(encrypted, aad);
            JsonNode stored = new ObjectMapper().readTree(plaintext);
            assertEquals(syntheticSubject, stored.path("subject").asText());
            assertEquals(syntheticBody, stored.path("plainTextBody").asText());

            assertCannotDecrypt(encrypted, vault, DraftAad.payload(UUID.randomUUID(), response.id(), mailboxId));
            assertCannotDecrypt(encrypted, vault, DraftAad.payload(appUserId, UUID.randomUUID(), mailboxId));
            assertCannotDecrypt(encrypted, vault, DraftAad.payload(appUserId, response.id(), UUID.randomUUID()));
        } finally {
            if (encrypted != null) encrypted.destroy();
            wipe(expectedBody);
            wipe(expectedSubject);
            wipe(aad);
            wipe(plaintext);
            repository.clearCapturedPayload();
        }
    }

    @Test
    void stagedAttachmentBytesAreEncryptedAndBoundToTheirDraftAndAttachmentBeforePersistence() throws Exception {
        UUID appUserId = UUID.randomUUID();
        UUID mailboxId = UUID.randomUUID();
        byte[] attachmentBytes = "synthetic attachment bytes 72a9".getBytes(StandardCharsets.UTF_8);
        MemoryVaultRepository vaultRepository = new MemoryVaultRepository();
        VaultService vault = createVault(vaultRepository, "draft-attachment");
        CapturingDraftRepository repository = new CapturingDraftRepository(appUserId, mailboxId);
        DraftService service = createDraftService(repository, vault);
        EastUserPrincipal principal = principal(appUserId);
        DraftResponse draft = null;
        ByteArrayMultipartFile file = new ByteArrayMultipartFile(
                "upload", "synthetic-image.png", "image/png", attachmentBytes);
        EncryptedPayload encrypted = null;
        byte[] aad = null;
        byte[] plaintext = null;

        try {
            draft = service.create(principal, new DraftWriteRequest(
                    mailboxId,
                    DraftKind.NEW,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    "Synthetic attachment test",
                    "Synthetic body",
                    null));
            DraftAttachmentSummary result = service.addAttachment(
                    principal,
                    draft.id(),
                    file);

            assertEquals("synthetic-image.png", result.filename());
            assertEquals(attachmentBytes.length, repository.attachmentByteSize);
            assertEquals(12, repository.attachmentNonce.length);
            assertEquals(attachmentBytes.length + 16, repository.attachmentCiphertext.length);
            assertFalse(contains(repository.attachmentCiphertext, attachmentBytes));

            encrypted = new EncryptedPayload(repository.attachmentNonce, repository.attachmentCiphertext);
            aad = DraftAad.attachment(appUserId, draft.id(), repository.attachmentId);
            plaintext = vault.decryptLocal(encrypted, aad);
            assertArrayEquals(attachmentBytes, plaintext);

            assertCannotDecrypt(encrypted, vault, DraftAad.attachment(UUID.randomUUID(), draft.id(), repository.attachmentId));
            assertCannotDecrypt(encrypted, vault, DraftAad.attachment(appUserId, UUID.randomUUID(), repository.attachmentId));
            assertCannotDecrypt(encrypted, vault, DraftAad.attachment(appUserId, draft.id(), UUID.randomUUID()));
        } finally {
            if (encrypted != null) encrypted.destroy();
            wipe(attachmentBytes);
            wipe(aad);
            wipe(plaintext);
            file.destroy();
            repository.clearCapturedPayload();
            repository.clearCapturedAttachment();
        }
    }

    @Test
    void sendWithoutExplicitConfirmationStopsBeforeRepositoryOrGmailAccess() {
        UUID appUserId = UUID.randomUUID();
        UUID mailboxId = UUID.randomUUID();
        MemoryVaultRepository vaultRepository = new MemoryVaultRepository();
        VaultService vault = createVault(vaultRepository, "unconfirmed-send");
        CapturingDraftRepository repository = new CapturingDraftRepository(appUserId, mailboxId);
        DraftService service = createDraftService(repository, vault);

        DraftException exception = assertThrows(
                DraftException.class,
                () -> service.send(
                        principal(appUserId),
                        UUID.randomUUID(),
                        new SendDraftRequest(false, Instant.parse("2026-11-09T00:00:00Z"), "A".repeat(43))));

        assertEquals(HttpStatus.BAD_REQUEST, exception.status());
        assertEquals(0, repository.repositoryCalls);
    }

    private VaultService createVault(MemoryVaultRepository repository, String keyDirectory) {
        LocalVaultKeyStore keyStore = new LocalVaultKeyStore(
                tempDir.resolve(keyDirectory).resolve("vault.key").toString());
        VaultService vault = new VaultService(repository, new VaultCryptoService(), null, keyStore);
        vault.initializeForLocalUse();
        return vault;
    }

    private static DraftService createDraftService(DraftRepository repository, VaultService vault) {
        return new DraftService(
                repository,
                vault,
                null,
                null,
                new ObjectMapper(),
                new DraftHtmlSanitizer(),
                null,
                null,
                Clock.fixed(Instant.parse("2026-11-09T00:00:00Z"), ZoneOffset.UTC),
                new NoopTransactionManager());
    }

    private static EastUserPrincipal principal(UUID appUserId) {
        return new EastUserPrincipal(appUserId, "synthetic-google-subject", "test@example.invalid", "Test user");
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

    private static void assertCannotDecrypt(EncryptedPayload encrypted, VaultService vault, byte[] associatedData) {
        try {
            assertThrows(
                    VaultCryptoService.InvalidVaultCiphertextException.class,
                    () -> vault.decryptLocal(encrypted, associatedData));
        } finally {
            wipe(associatedData);
        }
    }

    private static void wipe(byte[] value) {
        if (value != null) Arrays.fill(value, (byte) 0);
    }

    private static final class CapturingDraftRepository extends DraftRepository {
        private final UUID appUserId;
        private final UUID mailboxId;
        private final Instant now = Instant.parse("2026-11-09T00:00:00Z");
        private DraftRow draftRow;
        private byte[] payloadNonce;
        private byte[] payloadCiphertext;
        private UUID attachmentId;
        private long attachmentByteSize;
        private byte[] attachmentNonce;
        private byte[] attachmentCiphertext;
        private int repositoryCalls;

        private CapturingDraftRepository(UUID appUserId, UUID mailboxId) {
            super(null);
            this.appUserId = appUserId;
            this.mailboxId = mailboxId;
        }

        @Override
        boolean mailboxIsOwned(UUID ownerId, UUID candidateMailboxId) {
            repositoryCalls++;
            return appUserId.equals(ownerId) && mailboxId.equals(candidateMailboxId);
        }

        @Override
        Optional<String> ownedMailboxEmail(UUID ownerId, UUID candidateMailboxId) {
            repositoryCalls++;
            return mailboxIsOwned(ownerId, candidateMailboxId)
                    ? Optional.of("test@example.invalid")
                    : Optional.empty();
        }

        @Override
        int insertLocal(
                UUID draftId,
                UUID ownerId,
                UUID candidateMailboxId,
                UUID sourceThreadId,
                DraftKind kind,
                byte[] nonce,
                byte[] ciphertext) {
            repositoryCalls++;
            payloadNonce = Arrays.copyOf(nonce, nonce.length);
            payloadCiphertext = Arrays.copyOf(ciphertext, ciphertext.length);
            draftRow = new DraftRow(
                    draftId,
                    ownerId,
                    candidateMailboxId,
                    sourceThreadId,
                    kind.name(),
                    "LOCAL",
                    null,
                    payloadNonce,
                    payloadCiphertext,
                    now,
                    now,
                    null,
                    null,
                    null);
            return 1;
        }

        @Override
        Optional<DraftRow> findOwned(UUID ownerId, UUID draftId, boolean lockForUpdate) {
            repositoryCalls++;
            if (draftRow == null || !appUserId.equals(ownerId) || !draftRow.id().equals(draftId)) {
                return Optional.empty();
            }
            return Optional.of(new DraftRow(
                    draftRow.id(),
                    draftRow.appUserId(),
                    draftRow.mailboxId(),
                    draftRow.sourceThreadId(),
                    draftRow.kind(),
                    draftRow.state(),
                    draftRow.gmailDraftId(),
                    draftRow.payloadNonce(),
                    draftRow.payloadCiphertext(),
                    draftRow.createdAt(),
                    draftRow.updatedAt(),
                    draftRow.savedAt(),
                    draftRow.sideEffectOperation(),
                    draftRow.sideEffectStartedAt()));
        }

        @Override
        List<DraftAttachmentSummary> listAttachmentSummaries(UUID ownerId, UUID draftId) {
            repositoryCalls++;
            if (attachmentId == null || !appUserId.equals(ownerId) || !draftRow.id().equals(draftId)) {
                return List.of();
            }
            return List.of(new DraftAttachmentSummary(
                    attachmentId, "synthetic-image.png", "image/png", attachmentByteSize));
        }

        @Override
        List<AttachmentRow> listAttachments(UUID ownerId, UUID draftId) {
            repositoryCalls++;
            if (attachmentId == null || !appUserId.equals(ownerId) || !draftRow.id().equals(draftId)) {
                return List.of();
            }
            return List.of(new AttachmentRow(
                    attachmentId,
                    draftId,
                    "synthetic-image.png",
                    "image/png",
                    attachmentByteSize,
                    attachmentNonce,
                    attachmentCiphertext));
        }

        @Override
        AttachmentUsage attachmentUsage(UUID ownerId, UUID draftId) {
            repositoryCalls++;
            if (!appUserId.equals(ownerId) || draftRow == null || !draftRow.id().equals(draftId)) {
                return new AttachmentUsage(0, 0);
            }
            return new AttachmentUsage(attachmentId == null ? 0 : 1, attachmentByteSize);
        }

        @Override
        int insertAttachment(
                UUID id,
                UUID draftId,
                String filename,
                String mimeType,
                long byteSize,
                byte[] nonce,
                byte[] ciphertext) {
            repositoryCalls++;
            attachmentId = id;
            attachmentByteSize = byteSize;
            attachmentNonce = Arrays.copyOf(nonce, nonce.length);
            attachmentCiphertext = Arrays.copyOf(ciphertext, ciphertext.length);
            return 1;
        }

        @Override
        void touchLocalDraft(UUID ownerId, UUID draftId) { repositoryCalls++; }

        private void clearCapturedPayload() {
            if (draftRow != null) draftRow.destroyPayloadBuffers();
            draftRow = null;
            wipe(payloadNonce);
            wipe(payloadCiphertext);
            payloadNonce = null;
            payloadCiphertext = null;
        }

        private void clearCapturedAttachment() {
            wipe(attachmentNonce);
            wipe(attachmentCiphertext);
            attachmentNonce = null;
            attachmentCiphertext = null;
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

    private static final class ByteArrayMultipartFile implements MultipartFile {
        private final String name;
        private final String filename;
        private final String contentType;
        private final byte[] bytes;

        private ByteArrayMultipartFile(String name, String filename, String contentType, byte[] bytes) {
            this.name = name;
            this.filename = filename;
            this.contentType = contentType;
            this.bytes = Arrays.copyOf(bytes, bytes.length);
        }

        @Override
        public String getName() { return name; }

        @Override
        public String getOriginalFilename() { return filename; }

        @Override
        public String getContentType() { return contentType; }

        @Override
        public boolean isEmpty() { return bytes.length == 0; }

        @Override
        public long getSize() { return bytes.length; }

        @Override
        public byte[] getBytes() { return Arrays.copyOf(bytes, bytes.length); }

        @Override
        public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }

        @Override
        public void transferTo(File destination) throws IOException {
            java.nio.file.Files.write(destination.toPath(), bytes);
        }

        private void destroy() { wipe(bytes); }
    }
}

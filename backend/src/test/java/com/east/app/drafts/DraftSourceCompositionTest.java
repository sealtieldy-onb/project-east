package com.east.app.drafts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.east.app.auth.EastUserPrincipal;
import com.east.app.auth.GoogleAccessTokenService;
import com.east.app.ai.AIProviderClient;
import com.east.app.ai.AIProviderGateway;
import com.east.app.ai.AIProviderPrompt;
import com.east.app.ai.StructuredOutputContract;
import com.east.app.gmail.GmailApiClient;
import com.east.app.gmail.GmailCacheAad;
import com.east.app.mailread.MailReadRepository;
import com.east.app.vault.EncryptedPayload;
import com.east.app.vault.LocalVaultKeyStore;
import com.east.app.vault.VaultCryptoService;
import com.east.app.vault.VaultRecord;
import com.east.app.vault.VaultRepository;
import com.east.app.vault.VaultService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.AbstractClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.client.RestClient;

class DraftSourceCompositionTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID MAILBOX_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_MAILBOX_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID THREAD_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID MESSAGE_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final String GMAIL_THREAD_ID = "synthetic-gmail-thread";
    private static final String GMAIL_MESSAGE_ID = "synthetic-gmail-message";
    private static final String OWNER_EMAIL = "owner@example.invalid";

    @TempDir
    Path tempDir;

    @Test
    void replyDefaultsToReplyToAndRetainsGmailThreadingHeaders() throws Exception {
        Fixture fixture = new Fixture(tempDir.resolve("reply"), OWNER_ID, MAILBOX_ID);
        try {
            DraftResponse draft = fixture.service.create(
                    principal(OWNER_ID),
                    new DraftWriteRequest(
                            MAILBOX_ID,
                            DraftKind.REPLY,
                            THREAD_ID,
                            MESSAGE_ID,
                            null,
                            null,
                            null,
                            null,
                            "Thanks for the update.",
                            null));

            assertEquals("REPLY", draft.kind());
            assertEquals(THREAD_ID, draft.sourceThreadId());
            assertEquals(List.of("reply@example.invalid"), draft.to());
            assertEquals(List.of(), draft.cc());
            assertEquals(List.of(), draft.bcc());
            assertEquals("Re: Project update", draft.subject());
            assertTrue(draft.plainTextBody().contains("Thanks for the update."));
            assertTrue(draft.plainTextBody().contains("Original synthetic message body."));

            DraftPayload stored = fixture.decryptStoredPayload();
            assertEquals(GMAIL_THREAD_ID, stored.gmailThreadId());
            assertEquals("<source@example.invalid>", stored.inReplyTo());
            assertEquals("<root@example.invalid> <prior@example.invalid> <source@example.invalid>", stored.references());
            assertEquals(1, fixture.readRepository.messageReadCount); // Source metadata came from the encrypted local cache.
            assertEquals(0, fixture.repository.sourceAttachmentLookupCount);
        } finally {
            fixture.destroy();
        }
    }

    @Test
    void assistantOutputIsStoredAsEncryptedLocalDraftWithoutGmailMutation() throws Exception {
        Fixture fixture = new Fixture(tempDir.resolve("assistant-local"), OWNER_ID, MAILBOX_ID);
        String configurationVersion = "d".repeat(64);
        int[] providerCalls = {0};
        AIProviderGateway provider = new AIProviderGateway() {
            private final AIProviderClient.ProviderMetadata metadata = new AIProviderClient.ProviderMetadata(
                    "OPENAI_COMPATIBLE", "http://127.0.0.1:43127/v1", "synthetic-model", configurationVersion);

            @Override
            public AIProviderClient.ProviderMetadata providerMetadata() {
                return metadata;
            }

            @Override
            public <T> AIProviderClient.Completion<T> completeWithMetadata(
                    AIProviderPrompt prompt,
                    StructuredOutputContract<T> contract,
                    String expectedConfigurationVersion) {
                assertEquals(configurationVersion, expectedConfigurationVersion);
                providerCalls[0]++;
                ObjectNode output = new ObjectMapper().createObjectNode();
                output.put("subject", "Synthetic assistant subject");
                output.put("body", "Synthetic assistant body.");
                return new AIProviderClient.Completion<>(contract.validate(output), metadata);
            }
        };
        DraftAssistantSourceLoader noSource = (owner, mailbox, thread, message, kind) -> {
            throw new AssertionError("A new-message draft must not load a Gmail source.");
        };
        DraftAssistService assistant = new DraftAssistService(provider, fixture.service, noSource, fixture.objectMapper);
        try {
            DraftResponse generated = assistant.generate(
                    principal(OWNER_ID),
                    new DraftAssistRequest(
                            MAILBOX_ID, DraftKind.NEW, null, null,
                            List.of("recipient@example.invalid"), null, null, null,
                            "Write a concise synthetic follow-up.", true, configurationVersion));

            assertEquals("LOCAL", generated.state());
            assertEquals("Synthetic assistant subject", generated.subject());
            assertEquals("Synthetic assistant body.", generated.plainTextBody());
            assertEquals(1, providerCalls[0]);
            assertTrue(fixture.gmailRequests.uris.isEmpty(), "Assistant generation must not save or send through Gmail.");
            byte[] ciphertext = fixture.repository.row.payloadCiphertext();
            try {
                assertFalse(new String(ciphertext, StandardCharsets.UTF_8).contains("Synthetic assistant body."));
            } finally {
                wipe(ciphertext);
            }
            DraftPayload stored = fixture.decryptStoredPayload();
            assertEquals("Synthetic assistant body.", stored.plainTextBody());
            assertEquals("LOCAL", fixture.repository.row.state());
        } finally {
            fixture.destroy();
        }
    }

    @Test
    void replyAllDefaultsToReplyToAndDeduplicatesOriginalRecipientsAcrossToAndCc() throws Exception {
        Fixture fixture = new Fixture(tempDir.resolve("reply-all"), OWNER_ID, MAILBOX_ID);
        try {
            DraftResponse draft = fixture.service.create(
                    principal(OWNER_ID),
                    new DraftWriteRequest(
                            MAILBOX_ID,
                            DraftKind.REPLY_ALL,
                            THREAD_ID,
                            MESSAGE_ID,
                            null,
                            null,
                            null,
                            null,
                            "I will follow up.",
                            null));

            assertEquals(List.of("reply@example.invalid"), draft.to());
            assertEquals(List.of("other@example.invalid", "second@example.invalid"), draft.cc());
            assertFalse(draft.cc().contains(OWNER_EMAIL));
            assertFalse(draft.cc().contains("reply@example.invalid"));
            assertEquals(List.of(), draft.bcc());
            assertEquals("Re: Project update", draft.subject());
        } finally {
            fixture.destroy();
        }
    }

    @Test
    void forwardQuotesSourceWithoutCreatingReplyThreadHeaders() throws Exception {
        Fixture fixture = new Fixture(tempDir.resolve("forward"), OWNER_ID, MAILBOX_ID);
        try {
            DraftResponse draft = fixture.service.create(
                    principal(OWNER_ID),
                    new DraftWriteRequest(
                            MAILBOX_ID,
                            DraftKind.FORWARD,
                            THREAD_ID,
                            MESSAGE_ID,
                            List.of("forward-recipient@example.invalid"),
                            null,
                            null,
                            null,
                            "Please take a look.",
                            null));

            assertEquals("FORWARD", draft.kind());
            assertEquals(List.of("forward-recipient@example.invalid"), draft.to());
            assertEquals("Fwd: Project update", draft.subject());
            assertTrue(draft.plainTextBody().contains("Please take a look."));
            assertTrue(draft.plainTextBody().contains("---------- Forwarded message ----------"));
            assertTrue(draft.plainTextBody().contains("Original synthetic message body."));

            DraftPayload stored = fixture.decryptStoredPayload();
            assertEquals(THREAD_ID, draft.sourceThreadId());
            assertNull(stored.gmailThreadId());
            assertNull(stored.inReplyTo());
            assertNull(stored.references());
            assertEquals(1, fixture.repository.sourceAttachmentLookupCount);
        } finally {
            fixture.destroy();
        }
    }

    @Test
    void forwardStagesSourceAttachmentEncryptedByDefaultAndAllowsLocalRemoval() throws Exception {
        byte[] sourceBytes = "synthetic forwarded attachment bytes".getBytes(StandardCharsets.UTF_8);
        Fixture fixture = new Fixture(
                tempDir.resolve("forward-attachment"),
                OWNER_ID,
                MAILBOX_ID,
                List.of(new DraftRepository.SourceAttachmentRow(
                        "synthetic-gmail-attachment-id",
                        "invoice.pdf",
                        "application/pdf",
                        (long) sourceBytes.length)),
                sourceBytes);
        EncryptedPayload encrypted = null;
        byte[] nonce = null;
        byte[] ciphertext = null;
        byte[] aad = null;
        byte[] plaintext = null;
        try {
            DraftResponse draft = fixture.service.create(
                    principal(OWNER_ID),
                    new DraftWriteRequest(
                            MAILBOX_ID,
                            DraftKind.FORWARD,
                            THREAD_ID,
                            MESSAGE_ID,
                            List.of("forward-recipient@example.invalid"),
                            null,
                            null,
                            null,
                            "Please review the attached invoice.",
                            null));

            assertEquals(1, draft.attachments().size());
            DraftAttachmentSummary staged = draft.attachments().get(0);
            assertEquals("invoice.pdf", staged.filename());
            assertEquals("application/pdf", staged.mimeType());
            assertEquals(sourceBytes.length, staged.byteSize());
            assertEquals(1, fixture.repository.touchLocalDraftCalls);
            assertEquals(1, fixture.gmailRequests.uris.size());
            assertTrue(fixture.gmailRequests.uris.get(0).getPath().endsWith(
                    "/messages/" + GMAIL_MESSAGE_ID + "/attachments/synthetic-gmail-attachment-id"));

            DraftRepository.AttachmentRow stored = fixture.repository.storedAttachments.get(0);
            nonce = stored.contentNonce().clone();
            ciphertext = stored.contentCiphertext().clone();
            aad = DraftAad.attachment(OWNER_ID, draft.id(), staged.id());
            encrypted = new EncryptedPayload(nonce, ciphertext);
            plaintext = fixture.vault.decryptLocal(encrypted, aad);
            assertArrayEquals(sourceBytes, plaintext);
            assertEquals(sourceBytes.length + 16, stored.contentCiphertext().length);
            assertFalse(contains(stored.contentCiphertext(), sourceBytes));

            fixture.service.deleteAttachment(principal(OWNER_ID), draft.id(), staged.id());
            DraftResponse afterRemoval = fixture.service.get(principal(OWNER_ID), draft.id());
            assertTrue(afterRemoval.attachments().isEmpty());
            assertTrue(fixture.repository.storedAttachments.isEmpty());
            assertEquals(2, fixture.repository.touchLocalDraftCalls);
        } finally {
            if (encrypted != null) encrypted.destroy();
            wipe(nonce);
            wipe(ciphertext);
            wipe(aad);
            wipe(plaintext);
            wipe(sourceBytes);
            fixture.destroy();
        }
    }

    @Test
    void replyCannotUseThreadOwnedByAnotherUserOrDifferentMailbox() {
        Fixture foreignOwner = new Fixture(tempDir.resolve("foreign-owner"), OWNER_ID, MAILBOX_ID);
        try {
            DraftException ownerFailure = assertThrows(
                    DraftException.class,
                    () -> foreignOwner.service.create(
                            principal(OTHER_OWNER_ID),
                            replyRequest(MAILBOX_ID)));
            assertEquals(404, ownerFailure.status().value());
            assertEquals(0, foreignOwner.readRepository.messageReadCount);
            assertEquals(0, foreignOwner.repository.insertCalls);
        } finally {
            foreignOwner.destroy();
        }

        Fixture wrongMailbox = new Fixture(tempDir.resolve("wrong-mailbox"), OWNER_ID, MAILBOX_ID);
        try {
            DraftException mailboxFailure = assertThrows(
                    DraftException.class,
                    () -> wrongMailbox.service.create(
                            principal(OWNER_ID),
                            replyRequest(OTHER_MAILBOX_ID)));
            assertEquals(404, mailboxFailure.status().value());
            assertEquals(0, wrongMailbox.readRepository.messageReadCount);
            assertEquals(0, wrongMailbox.repository.insertCalls);
        } finally {
            wrongMailbox.destroy();
        }
    }

    private static DraftWriteRequest replyRequest(UUID mailboxId) {
        return new DraftWriteRequest(
                mailboxId,
                DraftKind.REPLY,
                THREAD_ID,
                MESSAGE_ID,
                null,
                null,
                null,
                null,
                "Synthetic reply",
                null);
    }

    private static EastUserPrincipal principal(UUID ownerId) {
        return new EastUserPrincipal(ownerId, "synthetic-google-subject", "user@example.invalid", "Test user");
    }

    private static final class Fixture {
        private final UUID ownerId;
        private final UUID mailboxId;
        private final VaultService vault;
        private final MemoryVaultRepository vaultRepository = new MemoryVaultRepository();
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final SyntheticMailReadRepository readRepository;
        private final SyntheticDraftRepository repository;
        private final DraftService service;
        private final SyntheticGmailRequestFactory gmailRequests;
        private final byte[] cacheNonce;
        private final byte[] cacheCiphertext;

        private Fixture(Path keyPath, UUID ownerId, UUID mailboxId) {
            this(keyPath, ownerId, mailboxId, List.of(), null);
        }

        private Fixture(
                Path keyPath,
                UUID ownerId,
                UUID mailboxId,
                List<DraftRepository.SourceAttachmentRow> sourceAttachments,
                byte[] gmailAttachmentBytes) {
            this.ownerId = ownerId;
            this.mailboxId = mailboxId;
            vault = new VaultService(
                    vaultRepository,
                    new VaultCryptoService(),
                    null,
                    new LocalVaultKeyStore(keyPath.toString()));
            vault.initializeForLocalUse();
            repository = new SyntheticDraftRepository(ownerId, mailboxId, sourceAttachments);
            EncryptedPayload cached = encryptSourceCache();
            try {
                cacheNonce = cached.nonce();
                cacheCiphertext = cached.ciphertext();
            } finally {
                cached.destroy();
            }
            readRepository = new SyntheticMailReadRepository(ownerId, mailboxId, cacheNonce, cacheCiphertext);
            gmailRequests = new SyntheticGmailRequestFactory(gmailAttachmentBytes);
            SyntheticAccessTokenService accessTokens = new SyntheticAccessTokenService();
            GmailApiClient gmail = new GmailApiClient(localRestClientBuilder(gmailRequests), objectMapper);
            DraftSourceService sourceService = new DraftSourceService(
                    readRepository,
                    repository,
                    accessTokens,
                    gmail,
                    vault,
                    objectMapper,
                    new DraftHtmlSanitizer(),
                    Clock.fixed(NOW, ZoneOffset.UTC));
            service = new DraftService(
                    repository,
                    vault,
                    null,
                    null,
                    objectMapper,
                    new DraftHtmlSanitizer(),
                    sourceService,
                    null,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    new NoopTransactionManager());
        }

        private EncryptedPayload encryptSourceCache() {
            byte[] plaintext = null;
            byte[] aad = null;
            try {
                ObjectNode root = objectMapper.createObjectNode();
                root.put("schemaVersion", 1);
                root.put("bodyText", "Original synthetic message body.");
                root.put("bodyHtml", "");
                ObjectNode headers = root.putObject("headers");
                headers.put("from", "Sender <sender@example.invalid>");
                headers.put("to", "Owner <owner@example.invalid>, Other <other@example.invalid>");
                headers.put("cc", "Second <second@example.invalid>, Owner <owner@example.invalid>, Reply <reply@example.invalid>, Other <other@example.invalid>");
                headers.put("reply-to", "Reply <reply@example.invalid>");
                headers.put("message-id", "<source@example.invalid>");
                headers.put("in-reply-to", "<prior@example.invalid>");
                headers.put("references", "<root@example.invalid> <prior@example.invalid>");
                plaintext = objectMapper.writeValueAsBytes(root);
                aad = GmailCacheAad.message(ownerId, mailboxId, GMAIL_THREAD_ID, GMAIL_MESSAGE_ID);
                return vault.encryptLocal(plaintext, aad);
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to prepare synthetic message cache.", exception);
            } finally {
                wipe(plaintext);
                wipe(aad);
            }
        }

        private DraftPayload decryptStoredPayload() throws Exception {
            DraftRepository.DraftRow row = repository.row;
            assertTrue(row != null);
            byte[] nonce = row.payloadNonce();
            byte[] ciphertext = row.payloadCiphertext();
            EncryptedPayload encrypted = new EncryptedPayload(nonce, ciphertext);
            byte[] aad = DraftAad.payload(ownerId, row.id(), mailboxId);
            byte[] plaintext = null;
            try {
                plaintext = vault.decryptLocal(encrypted, aad);
                return objectMapper.readValue(plaintext, DraftPayload.class);
            } finally {
                encrypted.destroy();
                wipe(nonce);
                wipe(ciphertext);
                wipe(aad);
                wipe(plaintext);
            }
        }

        private void destroy() {
            wipe(cacheNonce);
            wipe(cacheCiphertext);
            gmailRequests.destroy();
            readRepository.destroy();
            repository.destroy();
        }
    }

    private static final class SyntheticMailReadRepository extends MailReadRepository {
        private final UUID ownerId;
        private final UUID mailboxId;
        private final byte[] bodyNonce;
        private final byte[] bodyCiphertext;
        private int messageReadCount;

        private SyntheticMailReadRepository(UUID ownerId, UUID mailboxId, byte[] bodyNonce, byte[] bodyCiphertext) {
            super(null);
            this.ownerId = ownerId;
            this.mailboxId = mailboxId;
            this.bodyNonce = bodyNonce.clone();
            this.bodyCiphertext = bodyCiphertext.clone();
        }

        @Override
        public Optional<ThreadOwner> findOwnedThread(UUID candidateOwnerId, UUID threadId) {
            if (!ownerId.equals(candidateOwnerId) || !THREAD_ID.equals(threadId)) return Optional.empty();
            MailboxRow mailbox = new MailboxRow(mailboxId, OWNER_EMAIL, "Synthetic Owner", 0);
            return Optional.of(new ThreadOwner(THREAD_ID, mailboxId, GMAIL_THREAD_ID, "Project update", mailbox));
        }

        @Override
        public List<MessageRow> findMessages(UUID threadId) {
            messageReadCount++;
            if (!THREAD_ID.equals(threadId)) return List.of();
            return List.of(new MessageRow(
                    MESSAGE_ID,
                    GMAIL_MESSAGE_ID,
                    "sender@example.invalid",
                    "Sender",
                    NOW.minusSeconds(3600),
                    "text/plain",
                    bodyNonce.clone(),
                    bodyCiphertext.clone(),
                    NOW.plusSeconds(3600)));
        }

        private void destroy() {
            wipe(bodyNonce);
            wipe(bodyCiphertext);
        }
    }

    private static final class SyntheticDraftRepository extends DraftRepository {
        private final UUID ownerId;
        private final UUID mailboxId;
        private final List<SourceAttachmentRow> sourceAttachments;
        private final List<AttachmentRow> storedAttachments = new java.util.ArrayList<>();
        private DraftRow row;
        private int insertCalls;
        private int sourceAttachmentLookupCount;
        private int touchLocalDraftCalls;

        private SyntheticDraftRepository(
                UUID ownerId,
                UUID mailboxId,
                List<SourceAttachmentRow> sourceAttachments) {
            super(null);
            this.ownerId = ownerId;
            this.mailboxId = mailboxId;
            this.sourceAttachments = List.copyOf(sourceAttachments);
        }

        @Override
        boolean mailboxIsOwned(UUID candidateOwnerId, UUID candidateMailboxId) {
            return ownerId.equals(candidateOwnerId) && mailboxId.equals(candidateMailboxId);
        }

        @Override
        Optional<String> ownedMailboxEmail(UUID candidateOwnerId, UUID candidateMailboxId) {
            return mailboxIsOwned(candidateOwnerId, candidateMailboxId)
                    ? Optional.of(OWNER_EMAIL)
                    : Optional.empty();
        }

        @Override
        int insertLocal(
                UUID draftId,
                UUID candidateOwnerId,
                UUID candidateMailboxId,
                UUID sourceThreadId,
                DraftKind kind,
                byte[] nonce,
                byte[] ciphertext) {
            if (!mailboxIsOwned(candidateOwnerId, candidateMailboxId)) return 0;
            insertCalls++;
            row = new DraftRow(
                    draftId,
                    candidateOwnerId,
                    candidateMailboxId,
                    sourceThreadId,
                    kind.name(),
                    "LOCAL",
                    null,
                    nonce,
                    ciphertext,
                    NOW,
                    NOW,
                    null,
                    null,
                    null);
            return 1;
        }

        @Override
        Optional<DraftRow> findOwned(UUID candidateOwnerId, UUID draftId, boolean lockForUpdate) {
            if (row == null || !ownerId.equals(candidateOwnerId) || !row.id().equals(draftId)) return Optional.empty();
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
        List<DraftAttachmentSummary> listAttachmentSummaries(UUID candidateOwnerId, UUID draftId) {
            if (row == null || !ownerId.equals(candidateOwnerId) || !row.id().equals(draftId)) return List.of();
            return storedAttachments.stream()
                    .map(attachment -> new DraftAttachmentSummary(
                            attachment.id(),
                            attachment.filename(),
                            attachment.mimeType(),
                            attachment.byteSize()))
                    .toList();
        }

        @Override
        List<AttachmentRow> listAttachments(UUID candidateOwnerId, UUID draftId) {
            if (row == null || !ownerId.equals(candidateOwnerId) || !row.id().equals(draftId)) return List.of();
            return storedAttachments.stream()
                    .map(attachment -> new AttachmentRow(
                            attachment.id(),
                            attachment.draftId(),
                            attachment.filename(),
                            attachment.mimeType(),
                            attachment.byteSize(),
                            attachment.contentNonce().clone(),
                            attachment.contentCiphertext().clone()))
                    .toList();
        }

        @Override
        int insertAttachment(
                UUID attachmentId,
                UUID draftId,
                String filename,
                String mimeType,
                long byteSize,
                byte[] nonce,
                byte[] ciphertext) {
            storedAttachments.add(new AttachmentRow(
                    attachmentId,
                    draftId,
                    filename,
                    mimeType,
                    byteSize,
                    nonce.clone(),
                    ciphertext.clone()));
            return 1;
        }

        @Override
        void touchLocalDraft(UUID candidateOwnerId, UUID draftId) {
            if (row == null || !ownerId.equals(candidateOwnerId) || !row.id().equals(draftId)) {
                throw new AssertionError("Synthetic local-draft touch escaped its owner-scoped fixture.");
            }
            touchLocalDraftCalls++;
        }

        @Override
        boolean deleteAttachment(UUID candidateOwnerId, UUID draftId, UUID attachmentId) {
            if (row == null || !ownerId.equals(candidateOwnerId) || !row.id().equals(draftId)) return false;
            return storedAttachments.removeIf(attachment -> {
                if (!attachment.id().equals(attachmentId)) return false;
                wipe(attachment.contentNonce());
                wipe(attachment.contentCiphertext());
                return true;
            });
        }

        @Override
        List<SourceAttachmentRow> findOwnedSourceAttachments(
                UUID candidateOwnerId,
                UUID candidateMailboxId,
                UUID sourceThreadId,
                UUID sourceMessageId) {
            sourceAttachmentLookupCount++;
            if (!mailboxIsOwned(candidateOwnerId, candidateMailboxId)
                    || !THREAD_ID.equals(sourceThreadId)
                    || !MESSAGE_ID.equals(sourceMessageId)) return List.of();
            return sourceAttachments;
        }

        private void destroy() {
            if (row != null) {
                row.destroyPayloadBuffers();
                row = null;
            }
            storedAttachments.forEach(attachment -> {
                wipe(attachment.contentNonce());
                wipe(attachment.contentCiphertext());
            });
            storedAttachments.clear();
        }
    }

    private static final class SyntheticAccessTokenService extends GoogleAccessTokenService {
        private SyntheticAccessTokenService() {
            super(null, null, null, Clock.fixed(NOW, ZoneOffset.UTC));
        }

        @Override
        public synchronized String getValidAccessToken(UUID appUserId, UUID mailboxId) {
            if (!OWNER_ID.equals(appUserId) || !MAILBOX_ID.equals(mailboxId)) {
                throw new AssertionError("Forward attachment fetch escaped its synthetic mailbox.");
            }
            return "synthetic-forward-token";
        }
    }

    private static RestClient.Builder localRestClientBuilder(SyntheticGmailRequestFactory requestFactory) {
        RestClient.Builder local = RestClient.builder()
                .baseUrl("http://gmail.test/gmail/v1")
                .requestFactory(requestFactory);
        return (RestClient.Builder) Proxy.newProxyInstance(
                RestClient.Builder.class.getClassLoader(),
                new Class<?>[] {RestClient.Builder.class},
                (proxy, method, args) -> {
                    if ("baseUrl".equals(method.getName())
                            && args != null
                            && args.length == 1
                            && args[0] instanceof String) {
                        return local;
                    }
                    try {
                        return method.invoke(local, args);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    private static final class SyntheticGmailRequestFactory implements ClientHttpRequestFactory {
        private final byte[] attachmentBytes;
        private final List<URI> uris = new java.util.ArrayList<>();

        private SyntheticGmailRequestFactory(byte[] attachmentBytes) {
            this.attachmentBytes = attachmentBytes == null ? new byte[0] : attachmentBytes.clone();
        }

        private void destroy() {
            wipe(attachmentBytes);
            uris.clear();
        }

        @Override
        public org.springframework.http.client.ClientHttpRequest createRequest(URI uri, HttpMethod method) {
            return new AbstractClientHttpRequest() {
                private final ByteArrayOutputStream requestBody = new ByteArrayOutputStream();

                @Override public HttpMethod getMethod() { return method; }
                @Override public URI getURI() { return uri; }
                @Override protected OutputStream getBodyInternal(HttpHeaders headers) { return requestBody; }

                @Override
                protected ClientHttpResponse executeInternal(HttpHeaders headers) {
                    uris.add(uri);
                    String data = Base64.getUrlEncoder().withoutPadding().encodeToString(attachmentBytes);
                    String response = "{\"data\":\"" + data + "\",\"size\":" + attachmentBytes.length + "}";
                    return new SyntheticAttachmentResponse(response);
                }
            };
        }
    }

    private static final class SyntheticAttachmentResponse implements ClientHttpResponse {
        private final HttpHeaders headers;
        private final byte[] body;

        private SyntheticAttachmentResponse(String response) {
            HttpHeaders configured = new HttpHeaders();
            configured.setContentType(MediaType.APPLICATION_JSON);
            headers = HttpHeaders.readOnlyHttpHeaders(configured);
            body = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override public HttpStatus getStatusCode() { return HttpStatus.OK; }
        @Override public String getStatusText() { return "OK"; }
        @Override public HttpHeaders getHeaders() { return headers; }
        @Override public ByteArrayInputStream getBody() { return new ByteArrayInputStream(body); }
        @Override public void close() { }
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
            if (stored != null) throw new DuplicateKeyException("Synthetic vault already exists.");
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

    private static void wipe(byte[] value) {
        if (value != null) Arrays.fill(value, (byte) 0);
    }

    private static boolean contains(byte[] value, byte[] candidate) {
        if (value == null || candidate == null || candidate.length > value.length) return false;
        for (int start = 0; start <= value.length - candidate.length; start++) {
            int offset = 0;
            while (offset < candidate.length && value[start + offset] == candidate[offset]) offset++;
            if (offset == candidate.length) return true;
        }
        return false;
    }
}

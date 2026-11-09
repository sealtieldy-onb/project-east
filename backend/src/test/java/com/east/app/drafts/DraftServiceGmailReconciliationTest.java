package com.east.app.drafts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.east.app.auth.EastUserPrincipal;
import com.east.app.auth.GoogleAccessTokenService;
import com.east.app.gmail.GmailApiClient;
import com.east.app.vault.EncryptedPayload;
import com.east.app.vault.LocalVaultKeyStore;
import com.east.app.vault.VaultCryptoService;
import com.east.app.vault.VaultRecord;
import com.east.app.vault.VaultRepository;
import com.east.app.vault.VaultService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.client.RestClient;

class DraftServiceGmailReconciliationTest {

    private static final Instant NOW = Instant.parse("2026-11-09T00:00:00Z");
    private static final UUID APP_USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID MAILBOX_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID DRAFT_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final String GMAIL_DRAFT_ID = "gmail-draft-123";
    private static final String GMAIL_MESSAGE_ID = "gmail-message-123";
    private static final String GMAIL_THREAD_ID = "gmail-thread-123";
    private static final String MAILBOX_EMAIL = "test@example.invalid";
    private static final String RECIPIENT = "recipient@example.invalid";
    private static final String SUBJECT = "Synthetic reconciliation subject";
    private static final String BODY = "Synthetic reconciliation body";
    private static final String SYNTHETIC_TOKEN = "synthetic-access-token";

    @TempDir
    java.nio.file.Path tempDir;

    @Test
    void confirmedCompletedSaveRequiresMatchingGmailContentThenCommitsSavedState() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        VaultService vault = createVault("save-completed");
        InMemoryDraftRepository repository = pendingLocalSave(vault, mapper);
        TestAccessTokenService tokens = new TestAccessTokenService();
        RecordingRequestFactory requests = new RecordingRequestFactory(matchingDraftResource(BODY));
        DraftService service = createService(repository, tokens, vault, mapper, requests);

        DraftReconciliationResponse result = service.reconcile(
                principal(),
                DRAFT_ID,
                new DraftReconciliationRequest(true, "CONFIRMED_COMPLETED", GMAIL_DRAFT_ID));

        assertEquals(DRAFT_ID, result.draftId());
        assertEquals("SAVE_TO_GMAIL", result.operation());
        assertEquals("SAVED_TO_GMAIL", result.state());
        assertEquals(1, repository.markSavedCalls);
        assertEquals(0, repository.restoreCalls);
        assertEquals("SAVED_TO_GMAIL", repository.current.state());
        assertEquals(GMAIL_DRAFT_ID, repository.current.gmailDraftId());
        assertNull(repository.current.payloadNonce());
        assertNull(repository.current.payloadCiphertext());
        assertNull(repository.current.sideEffectOperation());
        assertEquals(1, tokens.tokenCalls);
        assertEquals(1, requests.requests.size());
        assertVerifiedDraftGet(requests.requests.getFirst());
    }

    @Test
    void mismatchedGmailContentLeavesUncertainSavePending() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        VaultService vault = createVault("save-mismatch");
        InMemoryDraftRepository repository = pendingLocalSave(vault, mapper);
        byte[] originalCiphertext = repository.current.payloadCiphertext();
        TestAccessTokenService tokens = new TestAccessTokenService();
        RecordingRequestFactory requests = new RecordingRequestFactory(matchingDraftResource("Different Gmail body"));
        DraftService service = createService(repository, tokens, vault, mapper, requests);

        DraftException exception = assertThrows(
                DraftException.class,
                () -> service.reconcile(
                        principal(),
                        DRAFT_ID,
                        new DraftReconciliationRequest(true, "CONFIRMED_COMPLETED", GMAIL_DRAFT_ID)));

        assertEquals(HttpStatus.CONFLICT, exception.status());
        assertEquals(0, repository.markSavedCalls);
        assertEquals(0, repository.restoreCalls);
        assertEquals("SAVE_PENDING", repository.current.state());
        assertEquals("SAVE_TO_GMAIL", repository.current.sideEffectOperation());
        assertTrue(java.util.Arrays.equals(originalCiphertext, repository.current.payloadCiphertext()));
        assertEquals(1, tokens.tokenCalls);
        assertEquals(1, requests.requests.size());
        assertVerifiedDraftGet(requests.requests.getFirst());
    }

    @Test
    void confirmedNotCompletedSavedSendChecksGmailDraftBeforeRestoringIt() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        VaultService vault = createVault("saved-send-not-completed");
        InMemoryDraftRepository repository = pendingSavedSend();
        TestAccessTokenService tokens = new TestAccessTokenService();
        RecordingRequestFactory requests = new RecordingRequestFactory(matchingDraftResource(BODY));
        DraftService service = createService(repository, tokens, vault, mapper, requests);

        DraftReconciliationResponse result = service.reconcile(
                principal(),
                DRAFT_ID,
                new DraftReconciliationRequest(true, "CONFIRMED_NOT_COMPLETED", null));

        assertEquals(DRAFT_ID, result.draftId());
        assertEquals("SEND", result.operation());
        assertEquals("SAVED_TO_GMAIL", result.state());
        assertEquals(1, repository.restoreCalls);
        assertEquals(0, repository.markSavedCalls);
        assertEquals(0, repository.deleteSendCalls);
        assertEquals("SAVED_TO_GMAIL", repository.current.state());
        assertEquals(GMAIL_DRAFT_ID, repository.current.gmailDraftId());
        assertNull(repository.current.sideEffectOperation());
        assertEquals(1, tokens.tokenCalls);
        assertEquals(1, requests.requests.size());
        assertVerifiedDraftGet(requests.requests.getFirst());
    }

    @Test
    void confirmedCompletedSavedSendDeletesPendingRecordWithoutRetryingGmailSend() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        VaultService vault = createVault("saved-send-completed");
        InMemoryDraftRepository repository = pendingSavedSend();
        TestAccessTokenService tokens = new TestAccessTokenService();
        RecordingRequestFactory requests = new RecordingRequestFactory("{}");
        DraftService service = createService(repository, tokens, vault, mapper, requests);

        DraftReconciliationResponse result = service.reconcile(
                principal(),
                DRAFT_ID,
                new DraftReconciliationRequest(true, "CONFIRMED_COMPLETED", null));

        assertEquals(DRAFT_ID, result.draftId());
        assertEquals("SEND", result.operation());
        assertEquals("SENT", result.state());
        assertEquals(1, repository.deleteSendCalls);
        assertNull(repository.current);
        assertEquals(0, repository.restoreCalls);
        assertEquals(0, repository.markSavedCalls);
        assertEquals(0, tokens.tokenCalls);
        assertTrue(requests.requests.isEmpty());
    }

    private InMemoryDraftRepository pendingLocalSave(VaultService vault, ObjectMapper mapper) throws Exception {
        DraftPayload payload = payload();
        byte[] plaintext = mapper.writeValueAsBytes(payload);
        EncryptedPayload encrypted = null;
        try {
            encrypted = vault.encryptLocal(plaintext, DraftAad.payload(APP_USER_ID, DRAFT_ID, MAILBOX_ID));
            return new InMemoryDraftRepository(new DraftRepository.DraftRow(
                    DRAFT_ID,
                    APP_USER_ID,
                    MAILBOX_ID,
                    null,
                    DraftKind.NEW.name(),
                    "SAVE_PENDING",
                    null,
                    encrypted.nonce(),
                    encrypted.ciphertext(),
                    NOW.minusSeconds(60),
                    NOW.minusSeconds(30),
                    null,
                    "SAVE_TO_GMAIL",
                    NOW.minusSeconds(30)));
        } finally {
            java.util.Arrays.fill(plaintext, (byte) 0);
            if (encrypted != null) encrypted.destroy();
        }
    }

    private InMemoryDraftRepository pendingSavedSend() {
        return new InMemoryDraftRepository(new DraftRepository.DraftRow(
                DRAFT_ID,
                APP_USER_ID,
                MAILBOX_ID,
                null,
                DraftKind.NEW.name(),
                "SEND_PENDING",
                GMAIL_DRAFT_ID,
                null,
                null,
                NOW.minusSeconds(60),
                NOW.minusSeconds(30),
                NOW.minusSeconds(45),
                "SEND",
                NOW.minusSeconds(30)));
    }

    private static DraftPayload payload() {
        return new DraftPayload(
                DraftKind.NEW,
                null,
                null,
                null,
                null,
                List.of(RECIPIENT),
                List.of(),
                List.of(),
                SUBJECT,
                BODY,
                "<p>" + BODY + "</p>");
    }

    private static String matchingDraftResource(String body) {
        String encodedBody = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(body.getBytes(StandardCharsets.UTF_8));
        return """
                {
                  "id":"%s",
                  "message":{
                    "id":"%s",
                    "threadId":"%s",
                    "payload":{
                      "mimeType":"text/plain",
                      "headers":[
                        {"name":"From","value":"%s"},
                        {"name":"To","value":"%s"},
                        {"name":"Subject","value":"%s"}
                      ],
                      "body":{"data":"%s"}
                    }
                  }
                }
                """.formatted(
                GMAIL_DRAFT_ID,
                GMAIL_MESSAGE_ID,
                GMAIL_THREAD_ID,
                MAILBOX_EMAIL,
                RECIPIENT,
                SUBJECT,
                encodedBody);
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
            InMemoryDraftRepository repository,
            TestAccessTokenService tokens,
            VaultService vault,
            ObjectMapper mapper,
            RecordingRequestFactory requests) {
        return new DraftService(
                repository,
                vault,
                tokens,
                new GmailApiClient(localRestClientBuilder(requests), mapper),
                mapper,
                new DraftHtmlSanitizer(),
                null,
                new DraftGmailReviewParser(new DraftHtmlSanitizer()),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new NoopTransactionManager());
    }

    private static RestClient.Builder localRestClientBuilder(RecordingRequestFactory requests) {
        RestClient.Builder local = RestClient.builder()
                .baseUrl("http://gmail.test/gmail/v1")
                .requestFactory(requests);
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

    private static void assertVerifiedDraftGet(RecordedRequest request) {
        assertEquals(HttpMethod.GET, request.method());
        assertEquals("/gmail/v1/users/me/drafts/" + GMAIL_DRAFT_ID, request.uri().getPath());
        assertEquals("full", org.springframework.web.util.UriComponentsBuilder.fromUri(request.uri())
                .build().getQueryParams().getFirst("format"));
        assertEquals("Bearer " + SYNTHETIC_TOKEN, request.headers().getFirst(HttpHeaders.AUTHORIZATION));
        assertTrue(request.headers().getAccept().contains(MediaType.APPLICATION_JSON));
    }

    private static EastUserPrincipal principal() {
        return new EastUserPrincipal(APP_USER_ID, "synthetic-subject", MAILBOX_EMAIL, "Synthetic user");
    }

    private record RecordedRequest(HttpMethod method, URI uri, HttpHeaders headers, String body) { }

    private static final class TestAccessTokenService extends GoogleAccessTokenService {
        private int tokenCalls;

        private TestAccessTokenService() {
            super(null, null, null, Clock.fixed(NOW, ZoneOffset.UTC));
        }

        @Override
        public synchronized String getValidAccessToken(UUID appUserId, UUID mailboxId) {
            assertEquals(APP_USER_ID, appUserId);
            assertEquals(MAILBOX_ID, mailboxId);
            tokenCalls++;
            return SYNTHETIC_TOKEN;
        }
    }

    private static final class InMemoryDraftRepository extends DraftRepository {
        private DraftRow current;
        private int markSavedCalls;
        private int restoreCalls;
        private int deleteSendCalls;

        private InMemoryDraftRepository(DraftRow seed) {
            super(null);
            current = copy(seed);
        }

        @Override
        Optional<DraftRow> findOwned(UUID appUserId, UUID draftId, boolean lockForUpdate) {
            if (current == null || !APP_USER_ID.equals(appUserId) || !current.id().equals(draftId)) {
                return Optional.empty();
            }
            return Optional.of(copy(current));
        }

        @Override
        Optional<String> ownedMailboxEmail(UUID appUserId, UUID mailboxId) {
            assertEquals(APP_USER_ID, appUserId);
            assertEquals(MAILBOX_ID, mailboxId);
            return Optional.of(MAILBOX_EMAIL);
        }

        @Override
        List<DraftAttachmentSummary> listAttachmentSummaries(UUID appUserId, UUID draftId) {
            assertEquals(APP_USER_ID, appUserId);
            assertEquals(DRAFT_ID, draftId);
            return List.of();
        }

        @Override
        List<AttachmentRow> listAttachments(UUID appUserId, UUID draftId) {
            assertEquals(APP_USER_ID, appUserId);
            assertEquals(DRAFT_ID, draftId);
            return List.of();
        }

        @Override
        int markSavedToGmail(UUID appUserId, UUID draftId, String gmailDraftId) {
            markSavedCalls++;
            if (!matches(appUserId, draftId, "SAVE_PENDING", "SAVE_TO_GMAIL")) return 0;
            current.destroyPayloadBuffers();
            current = new DraftRow(
                    current.id(), current.appUserId(), current.mailboxId(), current.sourceThreadId(),
                    current.kind(), "SAVED_TO_GMAIL", gmailDraftId, null, null,
                    current.createdAt(), NOW, NOW, null, null);
            return 1;
        }

        @Override
        int restorePendingSideEffect(UUID appUserId, UUID draftId, String operation) {
            restoreCalls++;
            if (current == null || !APP_USER_ID.equals(appUserId) || !DRAFT_ID.equals(draftId)
                    || !"SEND_PENDING".equals(current.state())
                    || !operation.equals(current.sideEffectOperation())) return 0;
            current.destroyPayloadBuffers();
            current = new DraftRow(
                    current.id(), current.appUserId(), current.mailboxId(), current.sourceThreadId(),
                    current.kind(), "SAVED_TO_GMAIL", current.gmailDraftId(), null, null,
                    current.createdAt(), NOW, current.savedAt(), null, null);
            return 1;
        }

        @Override
        boolean deletePendingSend(UUID appUserId, UUID draftId) {
            deleteSendCalls++;
            if (!matches(appUserId, draftId, "SEND_PENDING", "SEND")) return false;
            current.destroyPayloadBuffers();
            current = null;
            return true;
        }

        private boolean matches(UUID appUserId, UUID draftId, String state, String operation) {
            return current != null && APP_USER_ID.equals(appUserId) && DRAFT_ID.equals(draftId)
                    && state.equals(current.state()) && operation.equals(current.sideEffectOperation());
        }

        private static DraftRow copy(DraftRow row) {
            return new DraftRow(
                    row.id(), row.appUserId(), row.mailboxId(), row.sourceThreadId(), row.kind(), row.state(),
                    row.gmailDraftId(), row.payloadNonce(), row.payloadCiphertext(), row.createdAt(),
                    row.updatedAt(), row.savedAt(), row.sideEffectOperation(), row.sideEffectStartedAt());
        }
    }

    private static final class RecordingRequestFactory implements ClientHttpRequestFactory {
        private final String responseBody;
        private final java.util.ArrayList<RecordedRequest> requests = new java.util.ArrayList<>();

        private RecordingRequestFactory(String responseBody) {
            this.responseBody = responseBody;
        }

        @Override
        public org.springframework.http.client.ClientHttpRequest createRequest(URI uri, HttpMethod method) {
            return new AbstractClientHttpRequest() {
                private final ByteArrayOutputStream body = new ByteArrayOutputStream();

                @Override
                public HttpMethod getMethod() {
                    return method;
                }

                @Override
                public URI getURI() {
                    return uri;
                }

                @Override
                protected OutputStream getBodyInternal(HttpHeaders headers) {
                    return body;
                }

                @Override
                protected ClientHttpResponse executeInternal(HttpHeaders headers) {
                    HttpHeaders capturedHeaders = new HttpHeaders();
                    capturedHeaders.putAll(headers);
                    requests.add(new RecordedRequest(
                            method,
                            uri,
                            HttpHeaders.readOnlyHttpHeaders(capturedHeaders),
                            body.toString(StandardCharsets.UTF_8)));
                    return new SyntheticResponse(responseBody);
                }
            };
        }
    }

    private static final class SyntheticResponse implements ClientHttpResponse {
        private final HttpHeaders headers;
        private final byte[] body;

        private SyntheticResponse(String value) {
            HttpHeaders configured = new HttpHeaders();
            configured.setContentType(MediaType.APPLICATION_JSON);
            headers = HttpHeaders.readOnlyHttpHeaders(configured);
            body = value.getBytes(StandardCharsets.UTF_8);
        }

        @Override public HttpStatus getStatusCode() { return HttpStatus.OK; }
        @Override public String getStatusText() { return "OK"; }
        @Override public HttpHeaders getHeaders() { return headers; }
        @Override public ByteArrayInputStream getBody() { return new ByteArrayInputStream(body); }
        @Override public void close() { }
    }

    private static final class InMemoryVaultRepository extends VaultRepository {
        private VaultRecord record;

        private InMemoryVaultRepository() {
            super(null);
        }

        @Override
        public Optional<VaultRecord> find() {
            return Optional.ofNullable(record == null ? null : copy(record));
        }

        @Override
        public boolean isInitialized() {
            return record != null;
        }

        @Override
        public void create(byte[] salt, byte[] verificationMarker) {
            if (record != null) throw new DuplicateKeyException("vault already exists");
            record = new VaultRecord(salt, verificationMarker);
        }

        private static VaultRecord copy(VaultRecord value) {
            return new VaultRecord(value.salt(), value.encryptedVerificationMarker());
        }
    }

    private static final class NoopTransactionManager implements PlatformTransactionManager {
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }
        @Override public void commit(TransactionStatus status) { }
        @Override public void rollback(TransactionStatus status) { }
    }
}

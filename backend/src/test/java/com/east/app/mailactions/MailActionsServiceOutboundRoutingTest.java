package com.east.app.mailactions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.east.app.auth.EastUserPrincipal;
import com.east.app.auth.GoogleAccessTokenService;
import com.east.app.gmail.GmailApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.AbstractClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class MailActionsServiceOutboundRoutingTest {

    private static final UUID APP_USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID MAILBOX_A_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID MAILBOX_B_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID THREAD_A_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID THREAD_B_ID = UUID.fromString("30000000-0000-4000-8000-000000000002");

    @Test
    void combinedBulkActionRoutesEachGmailRequestThroughItsThreadMailboxToken() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        RecordingRequestFactory requestFactory = new RecordingRequestFactory();
        TestRepository repository = new TestRepository();
        repository.threads.put(
                THREAD_A_ID,
                new MailActionsRepository.OwnedThread(THREAD_A_ID, MAILBOX_A_ID, "gmail-thread-a"));
        repository.threads.put(
                THREAD_B_ID,
                new MailActionsRepository.OwnedThread(THREAD_B_ID, MAILBOX_B_ID, "gmail-thread-b"));
        TestAccessTokenService accessTokens = new TestAccessTokenService();
        accessTokens.tokens.put(MAILBOX_A_ID, "synthetic-token-a");
        accessTokens.tokens.put(MAILBOX_B_ID, "synthetic-token-b");

        GmailApiClient gmail = new GmailApiClient(localRestClientBuilder(requestFactory), objectMapper);
        MailActionsService service = new MailActionsService(repository, gmail, accessTokens);

        BulkThreadActionsResponse response = service.performBulkAction(
                principal(),
                new BulkThreadActionsRequest(
                        List.of(THREAD_A_ID, THREAD_B_ID), ThreadAction.MARK_READ, List.of()));

        assertEquals(List.of(true, true), response.outcomes().stream().map(ThreadActionOutcome::success).toList());
        assertEquals(
                List.of(
                        new TokenLookup(APP_USER_ID, MAILBOX_A_ID),
                        new TokenLookup(APP_USER_ID, MAILBOX_B_ID)),
                accessTokens.lookups);

        assertEquals(2, requestFactory.requests.size());
        assertGmailModifyRequest(
                requestFactory.requests.get(0), "synthetic-token-a", "gmail-thread-a", objectMapper);
        assertGmailModifyRequest(
                requestFactory.requests.get(1), "synthetic-token-b", "gmail-thread-b", objectMapper);

        assertEquals(
                List.of(
                        new ThreadStateUpdate(THREAD_A_ID, MAILBOX_A_ID, Boolean.FALSE),
                        new ThreadStateUpdate(THREAD_B_ID, MAILBOX_B_ID, Boolean.FALSE)),
                repository.stateUpdates);
    }

    private static void assertGmailModifyRequest(
            RecordedRequest request,
            String expectedToken,
            String expectedGmailThreadId,
            ObjectMapper objectMapper) throws Exception {
        assertEquals(HttpMethod.POST, request.method());
        assertEquals(
                "/gmail/v1/users/me/threads/" + expectedGmailThreadId + "/modify",
                request.uri().getPath());
        assertEquals("Bearer " + expectedToken, request.headers().getFirst(HttpHeaders.AUTHORIZATION));
        assertTrue(request.headers().getAccept().contains(MediaType.APPLICATION_JSON));

        JsonNode body = objectMapper.readTree(request.body());
        assertEquals(List.of("UNREAD"), strings(body.path("removeLabelIds")));
        assertEquals(List.of(), strings(body.path("addLabelIds")));
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private static RestClient.Builder localRestClientBuilder(RecordingRequestFactory requestFactory) {
        RestClient.Builder local = RestClient.builder()
                .baseUrl("http://gmail.test/gmail/v1")
                .requestFactory(requestFactory);

        // GmailApiClient applies its production base URL in its constructor. This builder proxy
        // retains a fake host while preserving the request factory for a fully in-memory request.
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

    private static EastUserPrincipal principal() {
        return new EastUserPrincipal(APP_USER_ID, "synthetic-subject", "owner@example.invalid", "Owner");
    }

    private record TokenLookup(UUID appUserId, UUID mailboxId) {
    }

    private record ThreadStateUpdate(UUID threadId, UUID mailboxId, Boolean unread) {
    }

    private record RecordedRequest(HttpMethod method, URI uri, HttpHeaders headers, String body) {
    }

    private static final class TestAccessTokenService extends GoogleAccessTokenService {

        private final Map<UUID, String> tokens = new HashMap<>();
        private final List<TokenLookup> lookups = new ArrayList<>();

        private TestAccessTokenService() {
            super(null, null, null, Clock.systemUTC());
        }

        @Override
        public synchronized String getValidAccessToken(UUID appUserId, UUID mailboxId) {
            lookups.add(new TokenLookup(appUserId, mailboxId));
            return tokens.get(mailboxId);
        }
    }

    private static final class TestRepository extends MailActionsRepository {

        private final Map<UUID, OwnedThread> threads = new HashMap<>();
        private final List<ThreadStateUpdate> stateUpdates = new ArrayList<>();

        private TestRepository() {
            super(null);
        }

        @Override
        OwnedThread findOwnedThread(UUID appUserId, UUID threadId) {
            return APP_USER_ID.equals(appUserId) ? threads.get(threadId) : null;
        }

        @Override
        public void synchronizeThreadState(
                UUID threadId,
                UUID mailboxId,
                List<String> addGmailLabelIds,
                List<String> removeGmailLabelIds,
                Boolean unread,
                Boolean starred) {
            stateUpdates.add(new ThreadStateUpdate(threadId, mailboxId, unread));
        }
    }

    private static final class RecordingRequestFactory implements ClientHttpRequestFactory {

        private final List<RecordedRequest> requests = new ArrayList<>();

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
                    return new SyntheticResponse();
                }
            };
        }
    }

    private static final class SyntheticResponse implements ClientHttpResponse {

        private final HttpHeaders headers;

        private SyntheticResponse() {
            HttpHeaders configuredHeaders = new HttpHeaders();
            configuredHeaders.setContentType(MediaType.APPLICATION_JSON);
            headers = HttpHeaders.readOnlyHttpHeaders(configuredHeaders);
        }

        @Override
        public HttpStatus getStatusCode() {
            return HttpStatus.OK;
        }

        @Override
        public String getStatusText() {
            return "OK";
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public ByteArrayInputStream getBody() {
            return new ByteArrayInputStream("{\"id\":\"synthetic-thread\",\"messages\":[]}".getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void close() {
        }
    }
}

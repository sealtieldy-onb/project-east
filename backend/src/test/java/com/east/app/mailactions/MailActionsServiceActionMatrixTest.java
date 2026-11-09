package com.east.app.mailactions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

class MailActionsServiceActionMatrixTest {

    private static final UUID APP_USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID MAILBOX_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID THREAD_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID LABEL_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final String GMAIL_THREAD_ID = "synthetic-gmail-thread";
    private static final String TOKEN = "synthetic-mailbox-token";

    @Test
    void supportedThreadActionsBuildExpectedMailboxScopedGmailRequests() throws Exception {
        Fixture fixture = new Fixture();
        List<ExpectedAction> actions = List.of(
                new ExpectedAction(ThreadAction.ARCHIVE, List.of(), List.of("INBOX"), null, null, "modify"),
                new ExpectedAction(ThreadAction.MOVE_TO_INBOX, List.of("INBOX"), List.of(), null, null, "modify"),
                new ExpectedAction(ThreadAction.TRASH, List.of("TRASH"), List.of("INBOX"), null, null, "trash"),
                new ExpectedAction(ThreadAction.UNTRASH, List.of("INBOX"), List.of("TRASH"), null, null, "untrash"),
                new ExpectedAction(ThreadAction.MARK_READ, List.of(), List.of("UNREAD"), false, null, "modify"),
                new ExpectedAction(ThreadAction.MARK_UNREAD, List.of("UNREAD"), List.of(), true, null, "modify"),
                new ExpectedAction(ThreadAction.SPAM, List.of("SPAM"), List.of("INBOX"), null, null, "modify"),
                new ExpectedAction(ThreadAction.NOT_SPAM, List.of("INBOX"), List.of("SPAM"), null, null, "modify"),
                new ExpectedAction(ThreadAction.STAR, List.of("STARRED"), List.of(), null, true, "modify"),
                new ExpectedAction(ThreadAction.UNSTAR, List.of(), List.of("STARRED"), null, false, "modify"),
                new ExpectedAction(ThreadAction.ADD_LABELS, List.of("Label_42"), List.of(), null, null, "modify"),
                new ExpectedAction(ThreadAction.REMOVE_LABELS, List.of(), List.of("Label_42"), null, null, "modify"));

        for (int index = 0; index < actions.size(); index++) {
            ExpectedAction expected = actions.get(index);
            fixture.service.performThreadAction(
                    principal(),
                    THREAD_ID,
                    new ThreadActionRequest(expected.action(), labelIdsFor(expected.action())));

            RecordedRequest request = fixture.requestFactory.requests.get(index);
            assertEquals(HttpMethod.POST, request.method(), expected.action().name());
            assertEquals(
                    "/gmail/v1/users/me/threads/" + GMAIL_THREAD_ID + "/" + expected.endpoint(),
                    request.uri().getPath(),
                    expected.action().name());
            assertEquals("Bearer " + TOKEN, request.headers().getFirst(HttpHeaders.AUTHORIZATION));
            assertTrue(request.headers().getAccept().contains(MediaType.APPLICATION_JSON));

            JsonNode body = fixture.objectMapper.readTree(request.body());
            if ("modify".equals(expected.endpoint())) {
                assertEquals(expected.addLabels(), strings(body.path("addLabelIds")), expected.action().name());
                assertEquals(expected.removeLabels(), strings(body.path("removeLabelIds")), expected.action().name());
            } else {
                assertEquals("{}", request.body(), expected.action().name());
            }
        }

        assertEquals(
                actions.size(),
                fixture.accessTokens.lookups.size(),
                "each action resolves a token for the owning mailbox");
        assertTrue(fixture.accessTokens.lookups.stream()
                .allMatch(lookup -> lookup.equals(new TokenLookup(APP_USER_ID, MAILBOX_ID))));
        assertEquals(actions.size(), fixture.repository.stateUpdates.size());

        for (int index = 0; index < actions.size(); index++) {
            ExpectedAction expected = actions.get(index);
            assertEquals(
                    new ThreadStateUpdate(
                            THREAD_ID,
                            MAILBOX_ID,
                            expected.addLabels(),
                            expected.removeLabels(),
                            expected.unread(),
                            expected.starred()),
                    fixture.repository.stateUpdates.get(index),
                    expected.action().name());
        }

        List<String> expectedEventOrder = new ArrayList<>();
        for (int index = 0; index < actions.size(); index++) {
            expectedEventOrder.add("gmail");
            expectedEventOrder.add("local");
        }
        assertEquals(expectedEventOrder, fixture.events,
                "local mailbox state is updated only after each Gmail request succeeds");
    }

    @Test
    void failedGmailActionDoesNotUpdateLocalMailboxState() {
        Fixture fixture = new Fixture();
        fixture.requestFactory.nextStatus = HttpStatus.FORBIDDEN;

        GmailApiClient.GmailApiException failure = assertThrows(
                GmailApiClient.GmailApiException.class,
                () -> fixture.service.performThreadAction(
                        principal(), THREAD_ID,
                        new ThreadActionRequest(ThreadAction.TRASH, List.of())));

        assertEquals(403, failure.getHttpStatus());
        assertEquals(1, fixture.requestFactory.requests.size());
        assertEquals(List.of(new TokenLookup(APP_USER_ID, MAILBOX_ID)), fixture.accessTokens.lookups);
        assertEquals(List.of(), fixture.repository.stateUpdates);
        assertEquals(List.of("gmail"), fixture.events);
    }

    private static List<UUID> labelIdsFor(ThreadAction action) {
        return action == ThreadAction.ADD_LABELS || action == ThreadAction.REMOVE_LABELS
                ? List.of(LABEL_ID)
                : List.of();
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private static EastUserPrincipal principal() {
        return new EastUserPrincipal(APP_USER_ID, "synthetic-subject", "owner@example.invalid", "Owner");
    }

    private record ExpectedAction(
            ThreadAction action,
            List<String> addLabels,
            List<String> removeLabels,
            Boolean unread,
            Boolean starred,
            String endpoint) {
    }

    private record TokenLookup(UUID appUserId, UUID mailboxId) {
    }

    private record ThreadStateUpdate(
            UUID threadId,
            UUID mailboxId,
            List<String> addLabels,
            List<String> removeLabels,
            Boolean unread,
            Boolean starred) {
    }

    private record RecordedRequest(HttpMethod method, URI uri, HttpHeaders headers, String body) {
    }

    private static final class Fixture {
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final List<String> events = new ArrayList<>();
        private final RecordingRequestFactory requestFactory = new RecordingRequestFactory(events);
        private final TestRepository repository = new TestRepository(events);
        private final TestAccessTokenService accessTokens = new TestAccessTokenService();
        private final MailActionsService service;

        private Fixture() {
            repository.threads.put(
                    THREAD_ID,
                    new MailActionsRepository.OwnedThread(THREAD_ID, MAILBOX_ID, GMAIL_THREAD_ID));
            repository.labels.put(
                    LABEL_ID,
                    new MailActionsRepository.UserLabel(LABEL_ID, "Label_42", "Synthetic label"));
            accessTokens.tokens.put(MAILBOX_ID, TOKEN);
            GmailApiClient gmail = new GmailApiClient(localRestClientBuilder(requestFactory), objectMapper);
            service = new MailActionsService(repository, gmail, accessTokens);
        }
    }

    private static RestClient.Builder localRestClientBuilder(RecordingRequestFactory requestFactory) {
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
        private final Map<UUID, UserLabel> labels = new HashMap<>();
        private final List<String> events;
        private final List<ThreadStateUpdate> stateUpdates = new ArrayList<>();

        private TestRepository(List<String> events) {
            super(null);
            this.events = events;
        }

        @Override
        OwnedThread findOwnedThread(UUID appUserId, UUID threadId) {
            return APP_USER_ID.equals(appUserId) ? threads.get(threadId) : null;
        }

        @Override
        List<UserLabel> findOwnedUserLabels(UUID appUserId, UUID mailboxId, List<UUID> labelIds) {
            if (!APP_USER_ID.equals(appUserId) || !MAILBOX_ID.equals(mailboxId)) return List.of();
            return labelIds.stream().map(labels::get).filter(label -> label != null).toList();
        }

        @Override
        public void synchronizeThreadState(
                UUID threadId,
                UUID mailboxId,
                List<String> addGmailLabelIds,
                List<String> removeGmailLabelIds,
                Boolean unread,
                Boolean starred) {
            events.add("local");
            stateUpdates.add(new ThreadStateUpdate(
                    threadId,
                    mailboxId,
                    List.copyOf(addGmailLabelIds),
                    List.copyOf(removeGmailLabelIds),
                    unread,
                    starred));
        }
    }

    private static final class RecordingRequestFactory implements ClientHttpRequestFactory {
        private final List<String> events;
        private final List<RecordedRequest> requests = new ArrayList<>();
        private HttpStatus nextStatus = HttpStatus.OK;

        private RecordingRequestFactory(List<String> events) {
            this.events = events;
        }

        @Override
        public org.springframework.http.client.ClientHttpRequest createRequest(URI uri, HttpMethod method) {
            return new AbstractClientHttpRequest() {
                private final ByteArrayOutputStream body = new ByteArrayOutputStream();
                private final HttpStatus responseStatus = nextStatus;

                {
                    nextStatus = HttpStatus.OK;
                }

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
                    events.add("gmail");
                    HttpHeaders capturedHeaders = new HttpHeaders();
                    capturedHeaders.putAll(headers);
                    requests.add(new RecordedRequest(
                            method,
                            uri,
                            HttpHeaders.readOnlyHttpHeaders(capturedHeaders),
                            body.toString(StandardCharsets.UTF_8)));
                    return new SyntheticResponse(responseStatus);
                }
            };
        }
    }

    private static final class SyntheticResponse implements ClientHttpResponse {
        private final HttpStatus status;
        private final HttpHeaders headers;

        private SyntheticResponse(HttpStatus status) {
            this.status = status;
            HttpHeaders configuredHeaders = new HttpHeaders();
            configuredHeaders.setContentType(MediaType.APPLICATION_JSON);
            headers = HttpHeaders.readOnlyHttpHeaders(configuredHeaders);
        }

        @Override
        public HttpStatus getStatusCode() {
            return status;
        }

        @Override
        public String getStatusText() {
            return status.getReasonPhrase();
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public ByteArrayInputStream getBody() {
            String response = status.isError()
                    ? "{\"error\":{\"code\":403,\"errors\":[{\"reason\":\"insufficientPermissions\"}]}}"
                    : "{}";
            return new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void close() {
        }
    }
}

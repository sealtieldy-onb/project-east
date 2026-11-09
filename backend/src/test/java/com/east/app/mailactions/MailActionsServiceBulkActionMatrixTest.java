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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
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

class MailActionsServiceBulkActionMatrixTest {

    private static final UUID APP_USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID MAILBOX_A_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID MAILBOX_B_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID THREAD_A_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID THREAD_B_ID = UUID.fromString("30000000-0000-4000-8000-000000000002");
    private static final UUID LABEL_A_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID LABEL_B_ID = UUID.fromString("40000000-0000-4000-8000-000000000002");
    private static final String GMAIL_THREAD_A = "gmail-thread-a";
    private static final String GMAIL_THREAD_B = "gmail-thread-b";
    private static final String TOKEN_A = "synthetic-token-a";
    private static final String TOKEN_B = "synthetic-token-b";

    @Test
    void supportedBulkActionsRouteToEachMailboxAndSynchronizeOnlyAfterSuccess() throws Exception {
        List<ExpectedAction> actions = List.of(
                new ExpectedAction(ThreadAction.ARCHIVE, List.of(), List.of("INBOX"), null, null, "modify"),
                new ExpectedAction(ThreadAction.MOVE_TO_INBOX, List.of("INBOX"), List.of(), null, null, "modify"),
                new ExpectedAction(ThreadAction.TRASH, List.of("TRASH"), List.of("INBOX"), null, null, "trash"),
                new ExpectedAction(ThreadAction.UNTRASH, List.of("INBOX"), List.of("TRASH"), null, null, "untrash"),
                new ExpectedAction(ThreadAction.MARK_READ, List.of(), List.of("UNREAD"), false, null, "modify"),
                new ExpectedAction(ThreadAction.MARK_UNREAD, List.of("UNREAD"), List.of(), true, null, "modify"),
                new ExpectedAction(ThreadAction.SPAM, List.of("SPAM"), List.of("INBOX"), null, null, "modify"),
                new ExpectedAction(ThreadAction.NOT_SPAM, List.of("INBOX"), List.of("SPAM"), null, null, "modify"));

        for (ExpectedAction action : actions) {
            verifyTwoMailboxAction(action);
        }
    }

    @Test
    void bulkLabelApplyAndRemoveUseEachMailboxesOwnLabelIds() throws Exception {
        for (ThreadAction action : List.of(ThreadAction.ADD_LABELS, ThreadAction.REMOVE_LABELS)) {
            Fixture fixture = new Fixture();
            BulkThreadActionsResponse accountA = fixture.service.performBulkAction(
                    principal(),
                    new BulkThreadActionsRequest(
                            List.of(THREAD_A_ID), action, List.of(LABEL_A_ID)));
            BulkThreadActionsResponse accountB = fixture.service.performBulkAction(
                    principal(),
                    new BulkThreadActionsRequest(
                            List.of(THREAD_B_ID), action, List.of(LABEL_B_ID)));

            assertEquals(List.of(true), accountA.outcomes().stream().map(ThreadActionOutcome::success).toList());
            assertEquals(List.of(true), accountB.outcomes().stream().map(ThreadActionOutcome::success).toList());
            assertEquals(
                    List.of(
                            new TokenLookup(APP_USER_ID, MAILBOX_A_ID),
                            new TokenLookup(APP_USER_ID, MAILBOX_B_ID)),
                    fixture.accessTokens.lookups);
            assertEquals(2, fixture.requestFactory.requests.size());
            assertModifyRequest(fixture.requestFactory.requests.get(0), TOKEN_A, GMAIL_THREAD_A,
                    action == ThreadAction.ADD_LABELS ? List.of("Label_A") : List.of(),
                    action == ThreadAction.REMOVE_LABELS ? List.of("Label_A") : List.of(), fixture.objectMapper);
            assertModifyRequest(fixture.requestFactory.requests.get(1), TOKEN_B, GMAIL_THREAD_B,
                    action == ThreadAction.ADD_LABELS ? List.of("Label_B") : List.of(),
                    action == ThreadAction.REMOVE_LABELS ? List.of("Label_B") : List.of(), fixture.objectMapper);
            assertEquals(
                    List.of(
                            new ThreadStateUpdate(THREAD_A_ID, MAILBOX_A_ID,
                                    action == ThreadAction.ADD_LABELS ? List.of("Label_A") : List.of(),
                                    action == ThreadAction.REMOVE_LABELS ? List.of("Label_A") : List.of(), null, null),
                            new ThreadStateUpdate(THREAD_B_ID, MAILBOX_B_ID,
                                    action == ThreadAction.ADD_LABELS ? List.of("Label_B") : List.of(),
                                    action == ThreadAction.REMOVE_LABELS ? List.of("Label_B") : List.of(), null, null)),
                    fixture.repository.stateUpdates);
            assertEquals(
                    List.of("gmail", "local", "gmail", "local"),
                    fixture.events,
                    "each mailbox label group is synchronized only after its own Gmail request succeeds");
        }
    }

    @Test
    void oneMailboxFailureDoesNotPreventOtherBulkItemsFromCompleting() throws Exception {
        Fixture fixture = new Fixture();
        fixture.requestFactory.enqueue(HttpStatus.FORBIDDEN);
        fixture.requestFactory.enqueue(HttpStatus.OK);

        BulkThreadActionsResponse response = fixture.service.performBulkAction(
                principal(),
                new BulkThreadActionsRequest(
                        List.of(THREAD_A_ID, THREAD_B_ID), ThreadAction.MARK_READ, List.of()));

        assertEquals(2, response.outcomes().size());
        assertEquals(THREAD_A_ID, response.outcomes().get(0).threadId());
        assertEquals(false, response.outcomes().get(0).success());
        assertEquals("GMAIL_API_ERROR", response.outcomes().get(0).errorCode());
        assertEquals(403, response.outcomes().get(0).gmailHttpStatus());
        assertEquals("insufficientPermissions", response.outcomes().get(0).googleErrorCode());
        assertEquals(THREAD_B_ID, response.outcomes().get(1).threadId());
        assertTrue(response.outcomes().get(1).success());

        assertEquals(
                List.of(
                        new TokenLookup(APP_USER_ID, MAILBOX_A_ID),
                        new TokenLookup(APP_USER_ID, MAILBOX_B_ID)),
                fixture.accessTokens.lookups);
        assertEquals(2, fixture.requestFactory.requests.size());
        assertModifyRequest(fixture.requestFactory.requests.get(0), TOKEN_A, GMAIL_THREAD_A,
                List.of(), List.of("UNREAD"), fixture.objectMapper);
        assertModifyRequest(fixture.requestFactory.requests.get(1), TOKEN_B, GMAIL_THREAD_B,
                List.of(), List.of("UNREAD"), fixture.objectMapper);
        assertEquals(
                List.of(new ThreadStateUpdate(THREAD_B_ID, MAILBOX_B_ID, List.of(), List.of("UNREAD"), false, null)),
                fixture.repository.stateUpdates);
        assertEquals(List.of("gmail", "gmail", "local"), fixture.events,
                "the failed first item's local state stays untouched while the second item proceeds");
    }

    private static void verifyTwoMailboxAction(ExpectedAction action) throws Exception {
        Fixture fixture = new Fixture();
        BulkThreadActionsResponse response = fixture.service.performBulkAction(
                principal(),
                new BulkThreadActionsRequest(List.of(THREAD_A_ID, THREAD_B_ID), action.action(), List.of()));

        assertEquals(List.of(true, true), response.outcomes().stream().map(ThreadActionOutcome::success).toList());
        assertEquals(
                List.of(
                        new TokenLookup(APP_USER_ID, MAILBOX_A_ID),
                        new TokenLookup(APP_USER_ID, MAILBOX_B_ID)),
                fixture.accessTokens.lookups);
        assertEquals(2, fixture.requestFactory.requests.size());
        assertRequest(fixture.requestFactory.requests.get(0), TOKEN_A, GMAIL_THREAD_A, action, fixture.objectMapper);
        assertRequest(fixture.requestFactory.requests.get(1), TOKEN_B, GMAIL_THREAD_B, action, fixture.objectMapper);
        assertEquals(
                List.of(
                        new ThreadStateUpdate(THREAD_A_ID, MAILBOX_A_ID,
                                action.addLabels(), action.removeLabels(), action.unread(), action.starred()),
                        new ThreadStateUpdate(THREAD_B_ID, MAILBOX_B_ID,
                                action.addLabels(), action.removeLabels(), action.unread(), action.starred())),
                fixture.repository.stateUpdates);
        assertEquals(List.of("gmail", "local", "gmail", "local"), fixture.events);
    }

    private static void assertRequest(
            RecordedRequest request,
            String token,
            String gmailThread,
            ExpectedAction expected,
            ObjectMapper objectMapper) throws Exception {
        assertEquals(HttpMethod.POST, request.method(), expected.action().name());
        assertEquals(
                "/gmail/v1/users/me/threads/" + gmailThread + "/" + expected.endpoint(),
                request.uri().getPath(),
                expected.action().name());
        assertEquals("Bearer " + token, request.headers().getFirst(HttpHeaders.AUTHORIZATION));
        assertTrue(request.headers().getAccept().contains(MediaType.APPLICATION_JSON));
        if ("modify".equals(expected.endpoint())) {
            assertModifyRequest(request, token, gmailThread,
                    expected.addLabels(), expected.removeLabels(), objectMapper);
        } else {
            assertEquals("{}", request.body(), expected.action().name());
        }
    }

    private static void assertModifyRequest(
            RecordedRequest request,
            String token,
            String gmailThread,
            List<String> addLabels,
            List<String> removeLabels,
            ObjectMapper objectMapper) throws Exception {
        assertEquals(HttpMethod.POST, request.method());
        assertEquals("/gmail/v1/users/me/threads/" + gmailThread + "/modify", request.uri().getPath());
        assertEquals("Bearer " + token, request.headers().getFirst(HttpHeaders.AUTHORIZATION));
        JsonNode body = objectMapper.readTree(request.body());
        assertEquals(addLabels, strings(body.path("addLabelIds")));
        assertEquals(removeLabels, strings(body.path("removeLabelIds")));
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

    private record LabeledUserLabel(UUID mailboxId, MailActionsRepository.UserLabel label) {
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
                    THREAD_A_ID,
                    new MailActionsRepository.OwnedThread(THREAD_A_ID, MAILBOX_A_ID, GMAIL_THREAD_A));
            repository.threads.put(
                    THREAD_B_ID,
                    new MailActionsRepository.OwnedThread(THREAD_B_ID, MAILBOX_B_ID, GMAIL_THREAD_B));
            repository.labels.put(
                    LABEL_A_ID,
                    new LabeledUserLabel(MAILBOX_A_ID,
                            new MailActionsRepository.UserLabel(LABEL_A_ID, "Label_A", "Synthetic A")));
            repository.labels.put(
                    LABEL_B_ID,
                    new LabeledUserLabel(MAILBOX_B_ID,
                            new MailActionsRepository.UserLabel(LABEL_B_ID, "Label_B", "Synthetic B")));
            accessTokens.tokens.put(MAILBOX_A_ID, TOKEN_A);
            accessTokens.tokens.put(MAILBOX_B_ID, TOKEN_B);
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
        private final Map<UUID, LabeledUserLabel> labels = new HashMap<>();
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
            if (!APP_USER_ID.equals(appUserId)) return List.of();
            return labelIds.stream()
                    .map(labels::get)
                    .filter(label -> label != null && label.mailboxId().equals(mailboxId))
                    .map(LabeledUserLabel::label)
                    .toList();
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
        private final Queue<HttpStatus> responseStatuses = new ArrayDeque<>();

        private RecordingRequestFactory(List<String> events) {
            this.events = events;
        }

        private void enqueue(HttpStatus status) {
            responseStatuses.add(status);
        }

        @Override
        public org.springframework.http.client.ClientHttpRequest createRequest(URI uri, HttpMethod method) {
            HttpStatus responseStatus = responseStatuses.isEmpty() ? HttpStatus.OK : responseStatuses.remove();
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

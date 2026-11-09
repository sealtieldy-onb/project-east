package com.east.app.mailactions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.east.app.auth.EastUserPrincipal;
import com.east.app.auth.GoogleAccessTokenService;
import com.east.app.gmail.GmailApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

class MailActionsServiceOwnershipTest {

    private static final UUID APP_USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID MAILBOX_A_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID MAILBOX_B_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID THREAD_A_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID THREAD_B_ID = UUID.fromString("30000000-0000-4000-8000-000000000002");
    private static final UUID LABEL_B_ID = UUID.fromString("40000000-0000-4000-8000-000000000002");

    @Test
    void threadNotOwnedByCurrentUserIsRejectedBeforeTokenLookupOrMailboxMutation() {
        TestRepository repository = new TestRepository();
        repository.threadOwners.put(THREAD_A_ID, OTHER_USER_ID);
        TestAccessTokenService accessTokens = new TestAccessTokenService();
        MailActionsService service = service(repository, accessTokens);

        MailActionsException failure = assertThrows(
                MailActionsException.class,
                () -> service.performThreadAction(
                        principal(APP_USER_ID),
                        THREAD_A_ID,
                        new ThreadActionRequest(ThreadAction.MARK_UNREAD, List.of())));

        assertEquals(HttpStatus.NOT_FOUND, failure.status());
        assertEquals("NOT_FOUND", failure.errorCode());
        assertEquals(List.of(), accessTokens.lookups);
        assertEquals(0, repository.synchronizeCalls);
    }

    @Test
    void labelFromAnotherMailboxIsRejectedWithinTheThreadMailboxBeforeTokenLookup() {
        TestRepository repository = new TestRepository();
        repository.threads.put(
                THREAD_A_ID,
                new MailActionsRepository.OwnedThread(THREAD_A_ID, MAILBOX_A_ID, "gmail-thread-a"));
        TestAccessTokenService accessTokens = new TestAccessTokenService();
        MailActionsService service = service(repository, accessTokens);

        MailActionsException failure = assertThrows(
                MailActionsException.class,
                () -> service.performThreadAction(
                        principal(APP_USER_ID),
                        THREAD_A_ID,
                        new ThreadActionRequest(ThreadAction.ADD_LABELS, List.of(LABEL_B_ID))));

        assertEquals(HttpStatus.NOT_FOUND, failure.status());
        assertEquals("LABEL_NOT_FOUND", failure.errorCode());
        assertEquals(APP_USER_ID, repository.labelLookupUserId);
        assertEquals(MAILBOX_A_ID, repository.labelLookupMailboxId);
        assertEquals(List.of(LABEL_B_ID), repository.labelLookupIds);
        assertEquals(List.of(), accessTokens.lookups);
        assertEquals(0, repository.synchronizeCalls);
    }

    @Test
    void bulkTokenLookupsUseEachThreadOwningMailboxAndStopBeforeGmailWhenTokenUnavailable() {
        TestRepository repository = new TestRepository();
        repository.threads.put(
                THREAD_A_ID,
                new MailActionsRepository.OwnedThread(THREAD_A_ID, MAILBOX_A_ID, "gmail-thread-a"));
        repository.threads.put(
                THREAD_B_ID,
                new MailActionsRepository.OwnedThread(THREAD_B_ID, MAILBOX_B_ID, "gmail-thread-b"));
        TestAccessTokenService accessTokens = new TestAccessTokenService();
        accessTokens.accessToken = null;
        MailActionsService service = service(repository, accessTokens);

        BulkThreadActionsResponse response = service.performBulkAction(
                principal(APP_USER_ID),
                new BulkThreadActionsRequest(
                        List.of(THREAD_A_ID, THREAD_B_ID), ThreadAction.MARK_READ, List.of()));

        assertEquals(
                List.of(
                        new TokenLookup(APP_USER_ID, MAILBOX_A_ID),
                        new TokenLookup(APP_USER_ID, MAILBOX_B_ID)),
                accessTokens.lookups);
        assertEquals(2, response.outcomes().size());
        assertEquals("ACCESS_TOKEN_UNAVAILABLE", response.outcomes().get(0).errorCode());
        assertEquals("ACCESS_TOKEN_UNAVAILABLE", response.outcomes().get(1).errorCode());
        assertEquals(0, repository.synchronizeCalls);
    }

    @Test
    void labelCrudForMailboxNotOwnedByUserIsRejectedBeforeTokenLookup() {
        TestRepository repository = new TestRepository();
        repository.mailboxOwned = false;
        TestAccessTokenService accessTokens = new TestAccessTokenService();
        MailActionsService service = service(repository, accessTokens);

        MailActionsException failure = assertThrows(
                MailActionsException.class,
                () -> service.createLabel(
                        principal(APP_USER_ID), MAILBOX_B_ID, new MailLabelRequest("Private label")));

        assertEquals(HttpStatus.NOT_FOUND, failure.status());
        assertEquals("NOT_FOUND", failure.errorCode());
        assertEquals(List.of(), accessTokens.lookups);
        assertEquals(0, repository.synchronizeCalls);
    }

    private static MailActionsService service(TestRepository repository, TestAccessTokenService accessTokens) {
        GmailApiClient gmailApiClient = new GmailApiClient(RestClient.builder(), new ObjectMapper());
        return new MailActionsService(repository, gmailApiClient, accessTokens);
    }

    private static EastUserPrincipal principal(UUID appUserId) {
        return new EastUserPrincipal(appUserId, "synthetic-google-subject", "owner@example.invalid", "Owner");
    }

    private record TokenLookup(UUID appUserId, UUID mailboxId) {
    }

    private static final class TestAccessTokenService extends GoogleAccessTokenService {

        private final List<TokenLookup> lookups = new ArrayList<>();
        private String accessToken = "synthetic-token";

        private TestAccessTokenService() {
            super(null, null, null, Clock.systemUTC());
        }

        @Override
        public synchronized String getValidAccessToken(UUID appUserId, UUID mailboxId) {
            lookups.add(new TokenLookup(appUserId, mailboxId));
            return accessToken;
        }
    }

    private static final class TestRepository extends MailActionsRepository {

        private final Map<UUID, OwnedThread> threads = new HashMap<>();
        private final Map<UUID, UUID> threadOwners = new HashMap<>();
        private UUID labelLookupUserId;
        private UUID labelLookupMailboxId;
        private List<UUID> labelLookupIds = List.of();
        private boolean mailboxOwned = true;
        private int synchronizeCalls;

        private TestRepository() {
            super(null);
        }

        @Override
        OwnedThread findOwnedThread(UUID appUserId, UUID threadId) {
            UUID ownerId = threadOwners.get(threadId);
            if (ownerId != null && !ownerId.equals(appUserId)) {
                return null;
            }
            return threads.get(threadId);
        }

        @Override
        List<UserLabel> findOwnedUserLabels(UUID appUserId, UUID mailboxId, List<UUID> labelIds) {
            labelLookupUserId = appUserId;
            labelLookupMailboxId = mailboxId;
            labelLookupIds = List.copyOf(labelIds);
            // The synthetic label belongs to another mailbox, so the mailbox-scoped repository
            // lookup correctly returns no labels for the selected thread.
            return List.of();
        }

        @Override
        boolean mailboxIsOwned(UUID appUserId, UUID mailboxId) {
            return mailboxOwned && APP_USER_ID.equals(appUserId) && MAILBOX_A_ID.equals(mailboxId);
        }

        @Override
        public void synchronizeThreadState(
                UUID threadId,
                UUID mailboxId,
                List<String> addGmailLabelIds,
                List<String> removeGmailLabelIds,
                Boolean unread,
                Boolean starred) {
            synchronizeCalls++;
        }
    }
}

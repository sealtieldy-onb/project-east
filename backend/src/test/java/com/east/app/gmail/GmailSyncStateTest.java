package com.east.app.gmail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.east.app.auth.GoogleAccessTokenService;
import com.east.app.auth.MailboxSyncStatus;
import com.east.app.gmail.GmailApiClient.GmailApiException;
import com.east.app.gmail.GmailSyncRepository.MailboxSyncBatch;
import com.east.app.gmail.GmailSyncRepository.SyncTarget;
import com.east.app.vault.VaultService;
import com.east.app.vault.VaultStatusResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GmailSyncStateTest {

    @Test
    void openingThreadWithUnexpiredBodyCacheDoesNotRequestFullMessageBodies() {
        Fixture fixture = new Fixture(null);
        SyncTarget target = fixture.repository.targets.getFirst();
        fixture.repository.unexpiredBodyIds = Set.of("synthetic-cached-message");
        fixture.repository.threadMessageIds = Set.of("synthetic-cached-message");
        fixture.gmail.threadResponse = fixture.gmail.json("""
                {"id":"synthetic-thread","messages":[
                  {"id":"synthetic-cached-message","threadId":"synthetic-thread","labelIds":["INBOX"]}
                ]}
                """);

        fixture.service.loadThreadOnDemand(target.appUserId(), target.mailboxId(), "synthetic-thread");

        assertEquals("metadata", fixture.gmail.lastThreadFormat);
        assertEquals(0, fixture.gmail.fullMessageBodyRequests);
        assertTrue(fixture.repository.lastOnDemandBatch.messages().isEmpty());
    }

    @Test
    void successfulInitialSyncMovesMailboxToReady() {
        Fixture fixture = new Fixture(null);

        GmailSyncService.MailboxSyncResult result = fixture.service.syncConnectedMailboxes().getFirst();

        assertTrue(result.success());
        assertEquals(MailboxSyncStatus.READY, fixture.repository.syncStatus);
        assertEquals(1, fixture.repository.fullSyncCommits);
    }

    @Test
    void resourceUnauthorizedInvalidatesTokenAndPersistsReconnectState() {
        Fixture fixture = new Fixture(null);
        fixture.gmail.profileFailure = new GmailApiException(401, "invalidCredentials");

        GmailSyncService.MailboxSyncResult result = fixture.service.syncConnectedMailboxes().getFirst();

        assertFalse(result.success());
        assertEquals(MailboxSyncStatus.NEEDS_REAUTHORIZATION, fixture.repository.syncStatus);
        assertEquals(1, fixture.accessTokens.reauthorizationMarks);
    }

    @Test
    void rateLimitFailureIsRetryableErrorAndDoesNotRequestReauthorization() {
        Fixture fixture = new Fixture(null);
        fixture.gmail.profileFailure = new GmailApiException(429, "rateLimitExceeded");

        GmailSyncService.MailboxSyncResult result = fixture.service.syncConnectedMailboxes().getFirst();

        assertFalse(result.success());
        assertEquals(MailboxSyncStatus.ERROR, fixture.repository.syncStatus);
        assertEquals(0, fixture.accessTokens.reauthorizationMarks);
    }

    @Test
    void failureAfterCommittedHistoryDataIsPartialFailure() {
        Fixture fixture = new Fixture("old-history");
        fixture.gmail.firstHistoryPage = fixture.gmail.json("""
                {"historyId":"middle-history","nextPageToken":"page-2","history":[
                  {"messagesDeleted":[{"message":{"id":"synthetic-deleted-message"}}]}
                ]}
                """);
        fixture.gmail.followingHistoryFailure = new GmailApiException(503, "backendError");

        GmailSyncService.MailboxSyncResult result = fixture.service.syncConnectedMailboxes().getFirst();

        assertFalse(result.success());
        assertEquals(1, fixture.repository.partialDataCommits);
        assertEquals(MailboxSyncStatus.PARTIAL_FAILURE, fixture.repository.syncStatus);
        assertEquals(0, fixture.accessTokens.reauthorizationMarks);
    }

    @Test
    void failureBeforeAnyHistoryDataCommitIsError() {
        Fixture fixture = new Fixture("old-history");
        fixture.gmail.firstHistoryPage = fixture.gmail.json("""
                {"historyId":"middle-history","nextPageToken":"page-2","history":[]}
                """);
        fixture.gmail.followingHistoryFailure = new GmailApiException(503, "backendError");

        GmailSyncService.MailboxSyncResult result = fixture.service.syncConnectedMailboxes().getFirst();

        assertFalse(result.success());
        assertEquals(0, fixture.repository.partialDataCommits);
        assertEquals(MailboxSyncStatus.ERROR, fixture.repository.syncStatus);
    }

    private static final class Fixture {
        private final TestSyncRepository repository = new TestSyncRepository();
        private final TestAccessTokenService accessTokens = new TestAccessTokenService(repository);
        private final TestGmailApiClient gmail = new TestGmailApiClient();
        private final GmailSyncService service;

        private Fixture(String historyCursor) {
            repository.targets = List.of(new SyncTarget(
                    UUID.fromString("30125ec1-9c94-47fa-b3d9-014c1b4f9b0b"),
                    UUID.fromString("1d7a2b14-3c7f-44af-9b5e-213597d7a7c2"),
                    historyCursor));
            service = new GmailSyncService(
                    gmail,
                    repository,
                    accessTokens,
                    new UnlockedVault(),
                    new ObjectMapper(),
                    Clock.fixed(Instant.parse("2026-11-09T08:00:00Z"), java.time.ZoneOffset.UTC));
        }
    }

    private static final class TestSyncRepository extends GmailSyncRepository {
        private List<SyncTarget> targets = List.of();
        private MailboxSyncStatus syncStatus = MailboxSyncStatus.PENDING;
        private int fullSyncCommits;
        private int partialDataCommits;
        private Set<String> unexpiredBodyIds = Set.of();
        private Set<String> threadMessageIds = Set.of();
        private MailboxSyncBatch lastOnDemandBatch;

        private TestSyncRepository() {
            super(null);
        }

        @Override
        public List<SyncTarget> findAllTargets() {
            return targets;
        }

        @Override
        public int bodyCacheTtlDays(UUID appUserId) {
            return 60;
        }

        @Override
        public Optional<SyncTarget> findOwnedTarget(UUID appUserId, UUID mailboxId) {
            return targets.stream()
                    .filter(target -> target.appUserId().equals(appUserId) && target.mailboxId().equals(mailboxId))
                    .findFirst();
        }

        @Override
        public Set<String> findUnexpiredBodyMessageIds(UUID mailboxId, String gmailThreadId, Instant now) {
            return unexpiredBodyIds;
        }

        @Override
        public Set<String> findThreadMessageIds(UUID mailboxId, String gmailThreadId) {
            return new java.util.HashSet<>(threadMessageIds);
        }

        @Override
        public void persistOnDemand(MailboxSyncBatch batch) {
            lastOnDemandBatch = batch;
        }

        @Override
        public void markSyncing(UUID appUserId, UUID mailboxId) {
            if (syncStatus != MailboxSyncStatus.NEEDS_REAUTHORIZATION) {
                syncStatus = MailboxSyncStatus.SYNCING;
            }
        }

        @Override
        public void markSyncFailure(UUID appUserId, UUID mailboxId, MailboxSyncStatus status) {
            if (syncStatus != MailboxSyncStatus.NEEDS_REAUTHORIZATION) syncStatus = status;
        }

        @Override
        public void persist(MailboxSyncBatch batch) {
            fullSyncCommits += 1;
            syncStatus = MailboxSyncStatus.READY;
        }

        @Override
        public void persistPartial(MailboxSyncBatch batch) {
            if (!batch.messages().isEmpty() || !batch.deletedGmailMessageIds().isEmpty()) partialDataCommits += 1;
        }

        @Override
        public void refreshLabels(UUID mailboxId, List<GmailSyncRepository.GmailLabelMetadata> labels) { }

        @Override
        public void advanceCursor(UUID appUserId, UUID mailboxId, String historyCursor, Instant syncedAt) {
            syncStatus = MailboxSyncStatus.READY;
        }
    }

    private static final class TestAccessTokenService extends GoogleAccessTokenService {
        private final TestSyncRepository repository;
        private int reauthorizationMarks;

        private TestAccessTokenService(TestSyncRepository repository) {
            super(null, null, null, Clock.systemUTC());
            this.repository = repository;
        }

        @Override
        public synchronized String getValidAccessToken(UUID appUserId, UUID mailboxId) {
            return "synthetic-access-token";
        }

        @Override
        public synchronized void markReauthorizationRequired(UUID appUserId, UUID mailboxId) {
            reauthorizationMarks += 1;
            repository.syncStatus = MailboxSyncStatus.NEEDS_REAUTHORIZATION;
        }

        @Override
        public synchronized boolean markReauthorizationRequiredIfCurrentAccessToken(
                UUID appUserId, UUID mailboxId, String rejectedAccessToken) {
            if (!"synthetic-access-token".equals(rejectedAccessToken)) return false;
            markReauthorizationRequired(appUserId, mailboxId);
            return true;
        }
    }

    private static final class UnlockedVault extends VaultService {
        private UnlockedVault() {
            super(null, null, null, null);
        }

        @Override
        public synchronized VaultStatusResponse status() {
            return new VaultStatusResponse(true, true);
        }

        @Override
        public synchronized void requireUnlocked() { }
    }

    private static final class TestGmailApiClient implements GmailSyncGateway {
        private final ObjectMapper mapper = new ObjectMapper();
        private RuntimeException profileFailure;
        private RuntimeException followingHistoryFailure;
        private JsonNode firstHistoryPage;
        private JsonNode threadResponse = json("{\"id\":\"synthetic-thread\",\"messages\":[]}");
        private String lastThreadFormat;
        private int fullMessageBodyRequests;
        private int historyPageCalls;

        private TestGmailApiClient() {
            firstHistoryPage = json("""
                    {"historyId":"next-history","history":[]}
                    """);
        }

        @Override
        public JsonNode getProfile(String accessToken) {
            if (profileFailure != null) throw profileFailure;
            return json("{\"historyId\":\"initial-history\"}");
        }

        @Override
        public JsonNode listMessages(
                String accessToken,
                String query,
                Integer maxResults,
                String pageToken,
                List<String> labelIds,
                Boolean includeSpamTrash) {
            return json("{\"messages\":[]}");
        }

        @Override
        public JsonNode listLabels(String accessToken) {
            return json("""
                    {"labels":[{"id":"INBOX","name":"Inbox","type":"SYSTEM"}]}
                    """);
        }

        @Override
        public JsonNode getThread(String accessToken, String threadId, String format, List<String> metadataHeaders) {
            lastThreadFormat = format;
            return threadResponse;
        }

        @Override
        public JsonNode getThread(
                String accessToken,
                String threadId,
                String format,
                List<String> metadataHeaders,
                String fields) {
            throw new UnsupportedOperationException("Thread-detail fixtures are not used in sync-state tests.");
        }

        @Override
        public JsonNode getMessage(String accessToken, String messageId, String format, List<String> metadataHeaders) {
            if ("full".equals(format)) fullMessageBodyRequests += 1;
            throw new UnsupportedOperationException("Message-detail fixture was not expected.");
        }

        @Override
        public JsonNode getMessage(
                String accessToken,
                String messageId,
                String format,
                List<String> metadataHeaders,
                String fields) {
            if ("full".equals(format)) fullMessageBodyRequests += 1;
            throw new UnsupportedOperationException("Message-detail fixture was not expected.");
        }

        @Override
        public JsonNode listHistory(
                String accessToken,
                String startHistoryId,
                Integer maxResults,
                String pageToken,
                String labelId,
                List<String> historyTypes) {
            historyPageCalls += 1;
            if (pageToken == null) return firstHistoryPage;
            if (followingHistoryFailure != null) throw followingHistoryFailure;
            return json("{\"historyId\":\"final-history\",\"history\":[]}");
        }

        private JsonNode json(String content) {
            try {
                return mapper.readTree(content);
            } catch (Exception exception) {
                throw new AssertionError("Invalid synthetic Gmail JSON fixture.", exception);
            }
        }
    }
}

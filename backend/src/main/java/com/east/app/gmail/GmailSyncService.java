package com.east.app.gmail;

import com.east.app.auth.AuthFlowException;
import com.east.app.auth.GoogleAccessTokenService;
import com.east.app.auth.MailboxSyncStatus;
import com.east.app.attachments.AttachmentFilenameSanitizer;
import com.east.app.attachments.InlineImagePolicy;
import com.east.app.gmail.GmailApiClient.GmailApiException;
import com.east.app.gmail.GmailApiClient.GmailResponseTooLargeException;
import com.east.app.gmail.GmailSyncRepository.AttachmentMetadata;
import com.east.app.gmail.GmailSyncRepository.BodyUnavailableReason;
import com.east.app.gmail.GmailSyncRepository.CachedMessage;
import com.east.app.gmail.GmailSyncRepository.GmailLabelMetadata;
import com.east.app.gmail.GmailSyncRepository.MailboxSyncBatch;
import com.east.app.gmail.GmailSyncRepository.SyncTarget;
import com.east.app.vault.EncryptedPayload;
import com.east.app.vault.VaultService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.east.app.vault.VaultService.VaultLockedException;

/** Read-only, per-mailbox Gmail synchronization with encrypted local content caching. */
@Service
public class GmailSyncService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GmailSyncService.class);
    private static final int INITIAL_MESSAGE_LIMIT = 30;
    private static final int REPORT_MESSAGE_ENUMERATION_LIMIT = 10_000;
    private static final int HISTORY_PAGE_SIZE = 100;
    private static final int HISTORY_MESSAGE_CHUNK_SIZE = 50;
    private static final int HISTORY_DELETE_CHUNK_SIZE = 500;
    private static final int MAX_HISTORY_IDS_PER_PAGE = 5_000;
    private static final int MAX_HEADER_VALUE_CHARS = 8 * 1024;
    private static final int MAX_SNIPPET_CHARS = 1_024;
    private static final long MAX_MESSAGE_BODY_BYTES = 1L * 1024 * 1024;
    private static final long MAX_THREAD_BODY_BYTES = 4L * 1024 * 1024;
    private static final List<String> HISTORY_TYPES = List.of(
            "messageAdded", "messageDeleted", "labelAdded", "labelRemoved");
    private static final Pattern FROM_ADDRESS = Pattern.compile("^(.*?)\\s*<([^<>]+)>\\s*$");

    private final GmailSyncGateway gmailApiClient;
    private final GmailSyncRepository repository;
    private final GoogleAccessTokenService accessTokenService;
    private final VaultService vaultService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Set<UUID> activeSyncs = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public GmailSyncService(
            GmailSyncGateway gmailApiClient,
            GmailSyncRepository repository,
            GoogleAccessTokenService accessTokenService,
            VaultService vaultService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.gmailApiClient = gmailApiClient;
        this.repository = repository;
        this.accessTokenService = accessTokenService;
        this.vaultService = vaultService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Runs at startup and every 15 minutes while the local encryption key is available. */
    @Scheduled(
            initialDelayString = "${east.gmail.sync-initial-delay-ms:0}",
            fixedDelayString = "${east.gmail.sync-interval-ms:900000}")
    public void scheduledSync() {
        if (!vaultService.status().unlocked()) return;
        List<MailboxSyncResult> results = syncConnectedMailboxes();
        for (MailboxSyncResult result : results) {
            if (!result.success()) {
                LOGGER.warn("Gmail sync failed for mailbox {} ({})", result.mailboxId(), result.errorCode());
            }
        }
    }

    /** Syncs all connected mailboxes independently; one mailbox failure does not stop others. */
    public List<MailboxSyncResult> syncConnectedMailboxes() {
        if (!vaultService.status().unlocked()) return List.of();
        List<SyncTarget> targets;
        try {
            targets = repository.findAllTargets();
        } catch (RuntimeException exception) {
            LOGGER.warn("Gmail mailbox scan failed; mailbox content was not logged");
            return List.of();
        }

        List<MailboxSyncResult> results = new ArrayList<>(targets.size());
        for (SyncTarget target : targets) {
            if (!vaultService.status().unlocked()) {
                results.add(MailboxSyncResult.failure(target.mailboxId(), "vault_locked"));
                continue;
            }
            results.add(syncTarget(target));
        }
        return List.copyOf(results);
    }

    /** Entry point for a future user-initiated sync endpoint. */
    public MailboxSyncResult syncMailbox(UUID appUserId, UUID mailboxId) {
        if (!vaultService.status().unlocked()) return MailboxSyncResult.failure(mailboxId, "vault_locked");
        return repository.findOwnedTarget(appUserId, mailboxId)
                .map(this::syncTarget)
                .orElseGet(() -> MailboxSyncResult.failure(mailboxId, "mailbox_not_found"));
    }

    /** Fetches and caches a thread only after the current user opens its detail view. */
    public void loadThreadOnDemand(UUID appUserId, UUID mailboxId, String gmailThreadId) {
        SyncTarget target = repository.findOwnedTarget(appUserId, mailboxId)
                .orElseThrow(() -> new IllegalArgumentException("The mailbox was not found."));
        if (!activeSyncs.add(mailboxId)) {
            throw new SyncInProgressException();
        }

        List<CachedMessage> cachedMessages = new ArrayList<>();
        String accessToken = null;
        try {
            vaultService.requireUnlocked();
            accessToken = accessTokenService.getValidAccessToken(appUserId, mailboxId);
            vaultService.requireUnlocked();
            // Thread metadata is sufficient to identify current messages. Never request a
            // full thread structure here: only individually fetch a body when its local cache
            // is absent or expired.
            JsonNode thread = gmailApiClient.getThread(
                    accessToken, gmailThreadId, "metadata", metadataHeaders());
            JsonNode messages = thread.path("messages");
            if (!messages.isArray() || messages.isEmpty()) {
                throw new IllegalStateException("Gmail returned an empty thread.");
            }

            String threadId = text(thread, "id");
            if (!StringUtils.hasText(threadId)) threadId = gmailThreadId;
            Instant fetchedAt = clock.instant();
            Instant expiresAt = fetchedAt.plus(Duration.ofDays(repository.bodyCacheTtlDays(appUserId)));
            Set<String> unexpiredBodyMessageIds = repository.findUnexpiredBodyMessageIds(
                    mailboxId, threadId, fetchedAt);
            Set<String> fetchedMessageIds = new LinkedHashSet<>();
            BodyBudget bodyBudget = new BodyBudget(MAX_THREAD_BODY_BYTES);
            for (JsonNode message : messages) {
                vaultService.requireUnlocked();
                String messageId = text(message, "id");
                if (!StringUtils.hasText(messageId)) continue;
                JsonNode bodyMessage;
                BodyUnavailableReason messageFallback;
                boolean attachmentMetadataComplete;
                if (unexpiredBodyMessageIds.contains(messageId)) {
                    // Persist refreshed metadata without replacing the still-valid encrypted
                    // body or attachment metadata. The repository treats this marker as an
                    // immediate retry only if the cached body expires before the write.
                    bodyMessage = message;
                    messageFallback = BodyUnavailableReason.ON_DEMAND;
                    attachmentMetadataComplete = false;
                } else {
                    messageFallback = null;
                    attachmentMetadataComplete = true;
                    try {
                        bodyMessage = gmailApiClient.getMessage(
                                accessToken,
                                messageId,
                                "full",
                                null,
                                GmailApiClient.MESSAGE_BODY_FIELDS);
                    } catch (GmailApiException exception) {
                        if (exception.getHttpStatus() == 404) continue;
                        throw exception;
                    } catch (GmailResponseTooLargeException exception) {
                        messageFallback = BodyUnavailableReason.MESSAGE_TOO_LARGE;
                        try {
                            bodyMessage = gmailApiClient.getMessage(
                                    accessToken,
                                    messageId,
                                    "full",
                                    null,
                                    GmailApiClient.MESSAGE_STRUCTURE_FIELDS);
                        } catch (GmailApiException metadataException) {
                            if (metadataException.getHttpStatus() == 404) continue;
                            throw metadataException;
                        } catch (GmailResponseTooLargeException metadataException) {
                            bodyMessage = gmailApiClient.getMessage(
                                    accessToken, messageId, "metadata", metadataHeaders());
                            attachmentMetadataComplete = false;
                        }
                    }
                }
                fetchedMessageIds.add(messageId);
                CachedMessage cached = toCachedMessage(
                        target,
                        bodyMessage,
                        new MessageDescriptor(messageId, threadId),
                        fetchedAt,
                        expiresAt,
                        bodyBudget,
                        messageFallback,
                        attachmentMetadataComplete,
                        false);
                if (cached != null) cachedMessages.add(cached);
            }

            vaultService.requireUnlocked();
            List<GmailLabelMetadata> labels = readLabels(accessToken);
            vaultService.requireUnlocked();
            Set<String> deletedMessageIds = repository.findThreadMessageIds(mailboxId, threadId);
            deletedMessageIds.removeAll(fetchedMessageIds);
            vaultService.requireUnlocked();
            repository.persistOnDemand(new MailboxSyncBatch(
                    appUserId,
                    mailboxId,
                    target.historyCursor(),
                    fetchedAt,
                    labels,
                    cachedMessages,
                    deletedMessageIds));
        } catch (RuntimeException exception) {
            destroyPayloads(cachedMessages);
            if (exception instanceof GmailApiException gmailException && gmailException.getHttpStatus() == 401) {
                boolean reauthorizationRequired = recordReauthorizationRequired(target, accessToken);
                gmailException.setMailboxReauthorizationRequired(reauthorizationRequired);
            }
            throw exception;
        } finally {
            activeSyncs.remove(mailboxId);
        }
        destroyPayloads(cachedMessages);
    }

    /**
     * Lists message identifiers for an explicitly requested report window. This is a metadata-only
     * Gmail read used to enforce the report's 30-message confirmation gate; it never fetches a
     * message body or invokes an AI provider.
     */
    public List<ReportMessageDescriptor> listReportMessages(
            UUID appUserId,
            UUID mailboxId,
            Instant startInclusive,
            Instant endExclusive) {
        if (startInclusive == null || endExclusive == null || !endExclusive.isAfter(startInclusive)) {
            throw new IllegalArgumentException("The report date range is invalid.");
        }
        SyncTarget target = repository.findOwnedTarget(appUserId, mailboxId)
                .orElseThrow(() -> new IllegalArgumentException("The mailbox was not found."));
        if (!activeSyncs.add(mailboxId)) throw new SyncInProgressException();

        String accessToken = null;
        try {
            vaultService.requireUnlocked();
            accessToken = accessTokenService.getValidAccessToken(appUserId, mailboxId);
            long after = Math.max(0, startInclusive.getEpochSecond() - 1);
            long before = endExclusive.getEpochSecond();
            String query = "after:" + after + " before:" + before + " -in:spam -in:trash";
            LinkedHashMap<String, ReportMessageDescriptor> descriptors = new LinkedHashMap<>();
            String pageToken = null;
            do {
                vaultService.requireUnlocked();
                JsonNode page = gmailApiClient.listMessages(
                        accessToken, query, 500, pageToken, null, false);
                JsonNode messages = page.path("messages");
                if (messages.isArray()) {
                    for (JsonNode message : messages) {
                        String messageId = text(message, "id");
                        String threadId = text(message, "threadId");
                        if (StringUtils.hasText(messageId) && StringUtils.hasText(threadId)) {
                            descriptors.putIfAbsent(messageId, new ReportMessageDescriptor(messageId, threadId));
                            if (descriptors.size() > REPORT_MESSAGE_ENUMERATION_LIMIT) {
                                throw new ReportWindowTooLargeException();
                            }
                        }
                    }
                }
                pageToken = text(page, "nextPageToken");
            } while (StringUtils.hasText(pageToken));
            return List.copyOf(descriptors.values());
        } catch (GmailApiException exception) {
            if (exception.getHttpStatus() == 401) {
                accessTokenService.markReauthorizationRequiredIfCurrentAccessToken(
                        appUserId, mailboxId, accessToken);
            }
            throw exception;
        } finally {
            activeSyncs.remove(mailboxId);
        }
    }

    /** Fetches and encrypts one report-selected message body after the user has approved the read. */
    public void cacheReportMessageBody(UUID appUserId, UUID mailboxId, ReportMessageDescriptor descriptor) {
        SyncTarget target = repository.findOwnedTarget(appUserId, mailboxId)
                .orElseThrow(() -> new IllegalArgumentException("The mailbox was not found."));
        if (descriptor == null
                || !StringUtils.hasText(descriptor.gmailMessageId())
                || !StringUtils.hasText(descriptor.gmailThreadId())) {
            throw new IllegalArgumentException("The selected report message is invalid.");
        }
        if (!activeSyncs.add(mailboxId)) throw new SyncInProgressException();

        String accessToken = null;
        CachedMessage cached = null;
        try {
            vaultService.requireUnlocked();
            accessToken = accessTokenService.getValidAccessToken(appUserId, mailboxId);
            Instant cachedAt = clock.instant();
            Instant expiresAt = cachedAt.plus(Duration.ofDays(repository.bodyCacheTtlDays(appUserId)));
            JsonNode message = gmailApiClient.getMessage(
                    accessToken,
                    descriptor.gmailMessageId(),
                    "full",
                    null,
                    GmailApiClient.MESSAGE_BODY_FIELDS);
            cached = toCachedMessage(
                    target,
                    message,
                    new MessageDescriptor(descriptor.gmailMessageId(), descriptor.gmailThreadId()),
                    cachedAt,
                    expiresAt,
                    new BodyBudget(MAX_MESSAGE_BODY_BYTES),
                    null,
                    false,
                    false);
            if (cached != null) {
                vaultService.requireUnlocked();
                repository.persistOnDemand(new MailboxSyncBatch(
                        appUserId, mailboxId, target.historyCursor(), cachedAt, List.of(),
                        List.of(cached), Set.of()));
            }
        } catch (GmailApiException exception) {
            if (exception.getHttpStatus() == 401) {
                accessTokenService.markReauthorizationRequiredIfCurrentAccessToken(
                        appUserId, mailboxId, accessToken);
            }
            throw exception;
        } catch (GmailResponseTooLargeException exception) {
            // A message too large to return within the local Gmail response limit cannot be sent
            // to the provider. Leave it out of this report and continue with other selected items.
        } finally {
            if (cached != null) destroyPayloads(List.of(cached));
            activeSyncs.remove(mailboxId);
        }
    }

    private MailboxSyncResult syncTarget(SyncTarget target) {
        if (!activeSyncs.add(target.mailboxId())) {
            return MailboxSyncResult.failure(target.mailboxId(), "sync_already_running");
        }
        SyncProgress progress = new SyncProgress();
        String accessToken = null;
        try {
            repository.markSyncing(target.appUserId(), target.mailboxId());
            vaultService.requireUnlocked();
            accessToken = accessTokenService.getValidAccessToken(target.appUserId(), target.mailboxId());
            if (!StringUtils.hasText(accessToken)) {
                recordRetryableFailure(target, progress);
                return MailboxSyncResult.failure(target.mailboxId(), "access_token_unavailable");
            }

            if (!StringUtils.hasText(target.historyCursor())) {
                return initialSync(target, accessToken);
            }

            return historySync(target, accessToken, progress);
        } catch (GmailApiException exception) {
            if (exception.getHttpStatus() == 401) {
                if (!recordReauthorizationRequired(target, accessToken)) {
                    recordRetryableFailure(target, progress);
                }
            } else {
                recordRetryableFailure(target, progress);
            }
            return MailboxSyncResult.failure(target.mailboxId(), safeGmailErrorCode(exception));
        } catch (AuthFlowException exception) {
            if (exception.status().value() == 401) {
                recordReauthorizationRequired(target);
            } else {
                recordRetryableFailure(target, progress);
            }
            return MailboxSyncResult.failure(target.mailboxId(), "auth_http_" + exception.status().value());
        } catch (VaultLockedException exception) {
            recordRetryableFailure(target, progress);
            return MailboxSyncResult.failure(target.mailboxId(), "vault_locked");
        } catch (RuntimeException exception) {
            recordRetryableFailure(target, progress);
            return MailboxSyncResult.failure(target.mailboxId(), safeRuntimeErrorCode(exception));
        } finally {
            activeSyncs.remove(target.mailboxId());
        }
    }

    private void recordReauthorizationRequired(SyncTarget target) {
        try {
            accessTokenService.markReauthorizationRequired(target.appUserId(), target.mailboxId());
        } catch (RuntimeException exception) {
            LOGGER.warn("Gmail authorization failure state could not be persisted for mailbox {}", target.mailboxId());
        }
    }

    private boolean recordReauthorizationRequired(SyncTarget target, String rejectedAccessToken) {
        try {
            return accessTokenService.markReauthorizationRequiredIfCurrentAccessToken(
                    target.appUserId(), target.mailboxId(), rejectedAccessToken);
        } catch (RuntimeException exception) {
            LOGGER.warn("Gmail authorization failure state could not be persisted for mailbox {}", target.mailboxId());
            return false;
        }
    }

    private void recordRetryableFailure(SyncTarget target, SyncProgress progress) {
        MailboxSyncStatus status = progress.hasCommittedPageData()
                ? MailboxSyncStatus.PARTIAL_FAILURE
                : MailboxSyncStatus.ERROR;
        try {
            repository.markSyncFailure(target.appUserId(), target.mailboxId(), status);
        } catch (RuntimeException exception) {
            LOGGER.warn("Gmail sync failure state could not be persisted for mailbox {}", target.mailboxId());
        }
    }

    /** Capture profile historyId before enumeration, so arrivals during the bounded list can be replayed. */
    private MailboxSyncResult initialSync(SyncTarget target, String accessToken) {
        vaultService.requireUnlocked();
        JsonNode profile = gmailApiClient.getProfile(accessToken);
        String startingHistoryId = text(profile, "historyId");
        if (!StringUtils.hasText(startingHistoryId)) {
            throw new IllegalStateException("Gmail profile did not contain a history cursor.");
        }

        LinkedHashMap<String, MessageDescriptor> descriptors = new LinkedHashMap<>();
        String pageToken = null;
        while (descriptors.size() < INITIAL_MESSAGE_LIMIT) {
            vaultService.requireUnlocked();
            int remaining = INITIAL_MESSAGE_LIMIT - descriptors.size();
            JsonNode page = gmailApiClient.listMessages(
                    accessToken,
                    null,
                    Math.min(remaining, INITIAL_MESSAGE_LIMIT),
                    pageToken,
                    null,
                    true);
            JsonNode messages = page.path("messages");
            if (messages.isArray()) {
                for (JsonNode message : messages) {
                    String id = text(message, "id");
                    String threadId = text(message, "threadId");
                    if (StringUtils.hasText(id) && StringUtils.hasText(threadId)) {
                        descriptors.putIfAbsent(id, new MessageDescriptor(id, threadId));
                        if (descriptors.size() >= INITIAL_MESSAGE_LIMIT) break;
                    }
                }
            }
            pageToken = text(page, "nextPageToken");
            if (!StringUtils.hasText(pageToken) || !messages.isArray() || messages.isEmpty()) break;
        }

        Instant syncStartedAt = clock.instant();
        int ttlDays = repository.bodyCacheTtlDays(target.appUserId());
        List<GmailLabelMetadata> labels = readLabels(accessToken);
        FetchedMessages fetched = fetchSyncMessages(
                target, accessToken, descriptors.values().stream().toList(), ttlDays, syncStartedAt);
        persistBatch(target, startingHistoryId, syncStartedAt, labels, fetched.messages(), fetched.deletedMessageIds());
        return MailboxSyncResult.success(target.mailboxId(), syncStartedAt);
    }

    private MailboxSyncResult historySync(SyncTarget target, String accessToken, SyncProgress progress) {
        Instant syncStartedAt = clock.instant();
        int ttlDays = repository.bodyCacheTtlDays(target.appUserId());
        List<GmailLabelMetadata> labels = readLabels(accessToken);
        repository.refreshLabels(target.mailboxId(), labels);
        String pageToken = null;
        String finalHistoryId = null;
        boolean firstPage = true;
        do {
            vaultService.requireUnlocked();
            JsonNode page;
            try {
                page = gmailApiClient.listHistory(
                        accessToken,
                        target.historyCursor(),
                        HISTORY_PAGE_SIZE,
                        pageToken,
                        null,
                        HISTORY_TYPES);
            } catch (GmailApiException exception) {
                if (firstPage && exception.getHttpStatus() == 404) return initialSync(target, accessToken);
                throw exception;
            }
            firstPage = false;
            String responseHistoryId = text(page, "historyId");
            String nextPageToken = text(page, "nextPageToken");
            LinkedHashSet<String> messageIds = new LinkedHashSet<>();
            LinkedHashSet<String> deletedMessageIds = new LinkedHashSet<>();
            JsonNode history = page.path("history");
            if (history.isArray()) {
                for (JsonNode change : history) {
                    collectIds(change.path("messagesAdded"), "message", messageIds);
                    collectIds(change.path("labelsAdded"), "message", messageIds);
                    collectIds(change.path("labelsRemoved"), "message", messageIds);
                    collectIds(change.path("messages"), null, messageIds);
                    collectIds(change.path("messagesDeleted"), "message", deletedMessageIds);
                }
            }
            messageIds.removeAll(deletedMessageIds);
            persistHistoryPage(
                    target,
                    accessToken,
                    ttlDays,
                    syncStartedAt,
                    messageIds,
                    deletedMessageIds,
                    progress);
            if (!StringUtils.hasText(nextPageToken)) {
                if (!StringUtils.hasText(responseHistoryId)) {
                    throw new IllegalStateException("Gmail history page did not contain a final history cursor.");
                }
                finalHistoryId = responseHistoryId;
            }
            pageToken = nextPageToken;
        } while (StringUtils.hasText(pageToken));

        // The user can lock the vault while Gmail is returning the final history page.
        // Do not commit a new cursor after a lock has stopped sync work.
        vaultService.requireUnlocked();
        repository.advanceCursor(target.appUserId(), target.mailboxId(), finalHistoryId, syncStartedAt);
        return MailboxSyncResult.success(target.mailboxId(), syncStartedAt);
    }

    private static void collectIds(JsonNode entries, String nestedField, Set<String> output) {
        if (!entries.isArray()) return;
        for (JsonNode entry : entries) {
            JsonNode message = nestedField == null ? entry : entry.path(nestedField);
            String id = text(message, "id");
            if (StringUtils.hasText(id)) {
                output.add(id);
                if (output.size() > MAX_HISTORY_IDS_PER_PAGE) {
                    throw new IllegalStateException("A Gmail history page exceeded East's local processing limit.");
                }
            }
        }
    }

    private void persistHistoryPage(
            SyncTarget target,
            String accessToken,
            int ttlDays,
            Instant syncedAt,
            Set<String> messageIds,
            Set<String> deletedMessageIds,
            SyncProgress progress) {
        vaultService.requireUnlocked();

        List<String> deleted = List.copyOf(deletedMessageIds);
        for (int offset = 0; offset < deleted.size(); offset += HISTORY_DELETE_CHUNK_SIZE) {
            List<String> chunk = deleted.subList(offset, Math.min(offset + HISTORY_DELETE_CHUNK_SIZE, deleted.size()));
            persistPartialBatch(target, syncedAt, List.of(), List.of(), new LinkedHashSet<>(chunk), progress);
        }

        List<MessageDescriptor> descriptors = messageIds.stream()
                .map(id -> new MessageDescriptor(id, null))
                .toList();
        for (int offset = 0; offset < descriptors.size(); offset += HISTORY_MESSAGE_CHUNK_SIZE) {
            vaultService.requireUnlocked();
            List<MessageDescriptor> chunk = descriptors.subList(
                    offset,
                    Math.min(offset + HISTORY_MESSAGE_CHUNK_SIZE, descriptors.size()));
            FetchedMessages fetched = fetchSyncMessages(target, accessToken, chunk, ttlDays, syncedAt);
            persistPartialBatch(target, syncedAt, List.of(), fetched.messages(), fetched.deletedMessageIds(), progress);
        }

        // An empty page still needs the final label/read-model cleanup before cursor advancement.
        if (deleted.isEmpty() && descriptors.isEmpty()) {
            persistPartialBatch(target, syncedAt, List.of(), List.of(), Set.of(), progress);
        }
    }

    private List<GmailLabelMetadata> readLabels(String accessToken) {
        vaultService.requireUnlocked();
        JsonNode response = gmailApiClient.listLabels(accessToken);
        JsonNode labels = response.path("labels");
        if (!labels.isArray()) throw new IllegalStateException("Gmail labels response was invalid.");
        List<GmailLabelMetadata> result = new ArrayList<>();
        for (JsonNode label : labels) {
            String id = text(label, "id");
            String name = text(label, "name");
            String type = text(label, "type");
            if (!StringUtils.hasText(id) || !StringUtils.hasText(name)) continue;
            String normalizedType = "SYSTEM".equalsIgnoreCase(type) ? "SYSTEM" : "USER";
            result.add(new GmailLabelMetadata(id, name, normalizedType));
        }
        if (result.isEmpty()) throw new IllegalStateException("Gmail labels response did not contain labels.");
        return List.copyOf(result);
    }

    private FetchedMessages fetchSyncMessages(
            SyncTarget target,
            String accessToken,
            List<MessageDescriptor> descriptors,
            int ttlDays,
            Instant cachedAt) {
        Instant expiresAt = cachedAt.plus(Duration.ofDays(ttlDays));
        Map<String, MessageDescriptor> unique = new LinkedHashMap<>();
        for (MessageDescriptor descriptor : descriptors) {
            unique.putIfAbsent(descriptor.messageId(), descriptor);
        }
        List<MessageDescriptor> sorted = List.copyOf(unique.values());
        List<CachedMessage> messages = new ArrayList<>();
        Set<String> deletedMessageIds = new LinkedHashSet<>();
        try {
            for (MessageDescriptor descriptor : sorted) {
                vaultService.requireUnlocked();
                JsonNode message;
                boolean attachmentMetadataComplete = true;
                BodyUnavailableReason fallbackReason = BodyUnavailableReason.ON_DEMAND;
                try {
                    message = gmailApiClient.getMessage(
                            accessToken,
                            descriptor.messageId(),
                            "full",
                            null,
                            GmailApiClient.MESSAGE_STRUCTURE_FIELDS);
                } catch (GmailApiException exception) {
                    if (exception.getHttpStatus() == 404) {
                        deletedMessageIds.add(descriptor.messageId());
                        continue;
                    }
                    throw exception;
                } catch (GmailResponseTooLargeException exception) {
                    try {
                        message = gmailApiClient.getMessage(
                                accessToken, descriptor.messageId(), "metadata", metadataHeaders());
                    } catch (GmailApiException metadataException) {
                        if (metadataException.getHttpStatus() == 404) {
                            deletedMessageIds.add(descriptor.messageId());
                            continue;
                        }
                        throw metadataException;
                    }
                    attachmentMetadataComplete = false;
                    fallbackReason = BodyUnavailableReason.MESSAGE_TOO_LARGE;
                }
                CachedMessage cached = toCachedMessage(
                        target,
                        message,
                        descriptor,
                        cachedAt,
                        expiresAt,
                        null,
                        fallbackReason,
                        attachmentMetadataComplete,
                        true);
                if (cached != null) messages.add(cached);
            }
        } catch (RuntimeException exception) {
            destroyPayloads(messages);
            throw exception;
        }

        messages.sort(Comparator.comparing(CachedMessage::sentAt)
                .thenComparing(CachedMessage::gmailMessageId));
        return new FetchedMessages(messages, deletedMessageIds);
    }

    private CachedMessage toCachedMessage(
            SyncTarget target,
            JsonNode message,
            MessageDescriptor descriptor,
            Instant cachedAt,
            Instant expiresAt,
            BodyBudget bodyBudget,
            BodyUnavailableReason fallbackReason,
            boolean attachmentMetadataComplete,
            boolean syncLimitRetryOnOpen) {
        String gmailMessageId = text(message, "id");
        if (!StringUtils.hasText(gmailMessageId)) gmailMessageId = descriptor.messageId();
        String gmailThreadId = text(message, "threadId");
        if (!StringUtils.hasText(gmailThreadId)) gmailThreadId = descriptor.threadId();
        if (!StringUtils.hasText(gmailThreadId)) {
            throw new IllegalStateException("Gmail message did not contain a thread identifier.");
        }

        JsonNode payload = message.path("payload");
        Map<String, String> headers = selectedHeaders(payload);
        String subject = headers.getOrDefault("subject", "");
        Sender sender = parseSender(headers.get("from"));
        Instant sentAt = parseSentAt(message, headers.get("date"));
        String snippet = nullableText(message, "snippet");
        if (snippet != null && snippet.length() > MAX_SNIPPET_CHARS) {
            snippet = snippet.substring(0, MAX_SNIPPET_CHARS);
        }
        BodyUnavailableReason bodyUnavailableReason = fallbackReason;
        MessageBodies bodies = new MessageBodies(null, null, null);
        List<String> labelIds = readLabelIds(message.path("labelIds"));
        List<AttachmentMetadata> attachments;
        try {
            if (bodyUnavailableReason == null) {
                BodyExtraction extraction = extractBodies(payload, gmailThreadId, bodyBudget);
                bodies = extraction.bodies();
                bodyUnavailableReason = extraction.unavailableReason();
            }
            attachments = attachmentMetadataComplete
                    ? extractAttachmentMetadata(payload)
                    : List.of();
        } finally {
            // Message body text has already been decoded into the encrypted cache payload below;
            // never keep Gmail's base64 part buffers, including sibling attachment data, in the tree.
            clearPartBodyData(payload);
        }

        byte[] bodyJson = serializeCachedContent(bodies, headers);
        EncryptedPayload encryptedBody = encryptAndWipe(bodyJson, GmailCacheAad.message(
                target.appUserId(), target.mailboxId(), gmailThreadId, gmailMessageId));

        EncryptedPayload encryptedSnippet = null;
        try {
            if (StringUtils.hasText(snippet)) {
                encryptedSnippet = encryptAndWipe(
                        snippet.getBytes(StandardCharsets.UTF_8),
                        GmailCacheAad.threadSnippet(target.appUserId(), target.mailboxId(), gmailThreadId));
            }

            Instant bodyExpiresAt = bodyUnavailableReason == BodyUnavailableReason.ON_DEMAND
                            || (bodyUnavailableReason == BodyUnavailableReason.SYNC_LIMIT && syncLimitRetryOnOpen)
                    ? cachedAt
                    : expiresAt;
            return new CachedMessage(
                    gmailMessageId,
                    gmailThreadId,
                    subject,
                    sender.email(),
                    sender.name(),
                    sentAt,
                    // Metadata-only background sync expires immediately so an explicit thread open
                    // can fetch just that message's body. A body omitted because the message is too
                    // large or exceeds the thread budget remains marked for the normal cache lifetime.
                    bodyExpiresAt,
                    // Snippets remain encrypted and visible only for the configured body-cache
                    // lifetime, even when the full body is marked for immediate on-demand fetch.
                    expiresAt,
                    encryptedBody,
                    encryptedSnippet,
                    labelIds,
                    attachments,
                    bodyUnavailableReason,
                    syncLimitRetryOnOpen,
                    attachmentMetadataComplete);
        } catch (RuntimeException exception) {
            encryptedBody.destroy();
            if (encryptedSnippet != null) encryptedSnippet.destroy();
            throw exception;
        }
    }

    private byte[] serializeCachedContent(MessageBodies bodies, Map<String, String> headers) {
        Map<String, Object> cached = new LinkedHashMap<>();
        cached.put("schemaVersion", 1);
        cached.put("bodyText", bodies.text());
        cached.put("bodyHtml", bodies.html());
        cached.put("bodyMimeType", bodies.preferredMimeType());
        Map<String, String> selected = new LinkedHashMap<>();
        copyHeader(headers, selected, "from", "from");
        copyHeader(headers, selected, "to", "to");
        copyHeader(headers, selected, "cc", "cc");
        copyHeader(headers, selected, "bcc", "bcc");
        copyHeader(headers, selected, "reply-to", "replyTo");
        copyHeader(headers, selected, "message-id", "messageId");
        copyHeader(headers, selected, "in-reply-to", "inReplyTo");
        copyHeader(headers, selected, "references", "references");
        cached.put("headers", selected);
        try {
            return objectMapper.writeValueAsBytes(cached);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode encrypted message content.");
        }
    }

    private static void copyHeader(Map<String, String> source, Map<String, String> target, String key, String targetKey) {
        String value = source.get(key);
        if (value != null) target.put(targetKey, value);
    }

    private EncryptedPayload encryptAndWipe(byte[] plaintext, byte[] associatedData) {
        try {
            return vaultService.encryptLocal(plaintext, associatedData);
        } finally {
            java.util.Arrays.fill(plaintext, (byte) 0);
        }
    }

    private static Map<String, String> selectedHeaders(JsonNode payload) {
        Set<String> allowed = Set.of(
                "from", "to", "cc", "bcc", "reply-to", "message-id", "in-reply-to", "references", "date", "subject");
        Map<String, String> headers = new LinkedHashMap<>();
        JsonNode entries = payload.path("headers");
        if (!entries.isArray()) return headers;
        for (JsonNode header : entries) {
            String name = nullableText(header, "name");
            String value = nullableText(header, "value");
            if (name == null || value == null || value.length() > MAX_HEADER_VALUE_CHARS) continue;
            String key = name.toLowerCase(Locale.ROOT);
            if (allowed.contains(key)) headers.putIfAbsent(key, value);
        }
        return headers;
    }

    private static Sender parseSender(String header) {
        if (!StringUtils.hasText(header)) return new Sender("", null);
        Matcher matcher = FROM_ADDRESS.matcher(header.trim());
        if (!matcher.matches()) return new Sender(null, header.trim());
        String name = matcher.group(1).trim().replaceAll("^\\\"|\\\"$", "");
        String email = matcher.group(2).trim();
        return new Sender(StringUtils.hasText(name) ? name : null, email);
    }

    private static Instant parseSentAt(JsonNode message, String headerDate) {
        JsonNode internalDate = message.path("internalDate");
        if (internalDate.isNumber() && internalDate.asLong() > 0) {
            return Instant.ofEpochMilli(internalDate.asLong());
        }
        if (internalDate.isTextual() && StringUtils.hasText(internalDate.asText())) {
            try {
                long epochMillis = Long.parseLong(internalDate.asText());
                if (epochMillis > 0) return Instant.ofEpochMilli(epochMillis);
            } catch (NumberFormatException ignored) {
                // Try the RFC 1123 Date header when Gmail's epoch timestamp is malformed.
            }
        }
        if (StringUtils.hasText(headerDate)) {
            try {
                return ZonedDateTime.parse(headerDate, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            } catch (DateTimeParseException ignored) {
                // Gmail normally supplies internalDate; do not write unparseable header text as a timestamp.
            }
        }
        throw new IllegalStateException("Gmail message timestamp was missing or invalid.");
    }

    private static List<String> readLabelIds(JsonNode labelIds) {
        if (!labelIds.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode labelId : labelIds) {
            if (labelId.isTextual() && StringUtils.hasText(labelId.asText())) result.add(labelId.asText());
        }
        return List.copyOf(result);
    }

    private static BodyExtraction extractBodies(JsonNode payload, String threadId, BodyBudget budget) {
        List<EncodedBodyPart> parts = new ArrayList<>();
        collectBodySources(payload, parts);
        long decodedBytes = 0;
        for (EncodedBodyPart part : parts) {
            decodedBytes = Math.addExact(decodedBytes, decodedLength(part.encodedData()));
        }
        if (decodedBytes == 0) return new BodyExtraction(new MessageBodies(null, null, null), null);
        BodyUnavailableReason unavailableReason = budget.check(threadId, decodedBytes);
        if (unavailableReason != null) {
            budget.noteExceeded(threadId, unavailableReason);
            return new BodyExtraction(new MessageBodies(null, null, null), unavailableReason);
        }

        StringBuilder plain = new StringBuilder();
        StringBuilder html = new StringBuilder();
        for (EncodedBodyPart part : parts) {
            byte[] decoded;
            try {
                decoded = Base64.getUrlDecoder().decode(part.encodedData());
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("Gmail message body encoding was invalid.");
            }
            try {
                String text = new String(decoded, StandardCharsets.UTF_8);
                if ("text/plain".equalsIgnoreCase(part.mimeType())) appendPart(plain, text);
                if ("text/html".equalsIgnoreCase(part.mimeType())) appendPart(html, text);
            } finally {
                java.util.Arrays.fill(decoded, (byte) 0);
            }
        }
        budget.record(threadId, decodedBytes);
        String plainBody = plain.length() == 0 ? null : plain.toString();
        String htmlBody = html.length() == 0 ? null : html.toString();
        String preferred = plainBody != null ? "text/plain" : htmlBody != null ? "text/html" : null;
        return new BodyExtraction(new MessageBodies(plainBody, htmlBody, preferred), null);
    }

    private static void collectBodySources(JsonNode part, List<EncodedBodyPart> output) {
        String filename = text(part, "filename");
        String mimeType = text(part, "mimeType");
        JsonNode body = part.path("body");
        String attachmentId = text(body, "attachmentId");
        String data = text(body, "data");
        if (!StringUtils.hasText(filename) && !StringUtils.hasText(attachmentId)
                && StringUtils.hasText(data)
                && ("text/plain".equalsIgnoreCase(mimeType) || "text/html".equalsIgnoreCase(mimeType))) {
            output.add(new EncodedBodyPart(mimeType, data));
        }
        JsonNode children = part.path("parts");
        if (children.isArray()) {
            for (JsonNode child : children) collectBodySources(child, output);
        }
    }

    private static long decodedLength(String encoded) {
        long length = encoded.length();
        if ((length & 3L) == 1L) throw new IllegalStateException("Gmail message body encoding was invalid.");
        long decoded = length * 3L / 4L;
        if (encoded.endsWith("==")) decoded -= 2;
        else if (encoded.endsWith("=")) decoded--;
        return Math.max(0, decoded);
    }

    private static void appendPart(StringBuilder output, String value) {
        if (output.length() > 0) output.append('\n');
        output.append(value);
    }

    private static List<AttachmentMetadata> extractAttachmentMetadata(JsonNode payload) {
        Map<String, AttachmentMetadata> attachments = new LinkedHashMap<>();
        collectAttachments(payload, attachments);
        return List.copyOf(attachments.values());
    }

    private static void collectAttachments(JsonNode part, Map<String, AttachmentMetadata> output) {
        String filename = text(part, "filename");
        String mimeType = text(part, "mimeType");
        JsonNode body = part.path("body");
        String attachmentId = text(body, "attachmentId");
        String partId = text(part, "partId");
        String contentId = contentId(part);
        boolean externalAttachment = StringUtils.hasText(attachmentId);
        boolean inlineAttachment = !externalAttachment && StringUtils.hasText(filename) && StringUtils.hasText(partId);
        boolean cidImage = !externalAttachment && StringUtils.hasText(partId)
                && contentId != null && InlineImagePolicy.supportsRaster(mimeType);
        if (externalAttachment || inlineAttachment || cidImage) {
            JsonNode sizeNode = body.path("size");
            Long size = sizeNode.isNumber() && sizeNode.asLong() >= 0 ? sizeNode.asLong() : null;
            String storedId = externalAttachment ? attachmentId : "inline:" + partId;
            String safeFilename = StringUtils.hasText(filename) ? filename : "attachment";
            output.putIfAbsent(storedId, new AttachmentMetadata(
                    storedId,
                    AttachmentFilenameSanitizer.sanitize(safeFilename),
                    StringUtils.hasText(mimeType) ? mimeType : "application/octet-stream",
                    size,
                    contentId));
        }
        JsonNode children = part.path("parts");
        if (children.isArray()) {
            for (JsonNode child : children) collectAttachments(child, output);
        }
    }

    private static String contentId(JsonNode part) {
        JsonNode headers = part.path("headers");
        if (!headers.isArray()) return null;
        for (JsonNode header : headers) {
            if ("content-id".equalsIgnoreCase(text(header, "name"))) {
                return GmailContentId.fromHeader(text(header, "value"));
            }
        }
        return null;
    }

    private static void clearPartBodyData(JsonNode part) {
        if (part == null || !part.isObject()) return;
        JsonNode body = part.path("body");
        if (body instanceof ObjectNode bodyObject) bodyObject.remove("data");
        JsonNode children = part.path("parts");
        if (children.isArray()) {
            for (JsonNode child : children) clearPartBodyData(child);
        }
    }

    private void persistBatch(
            SyncTarget target,
            String cursor,
            Instant syncedAt,
            List<GmailLabelMetadata> labels,
            List<CachedMessage> messages,
            Set<String> deletedMessageIds) {
        try {
            vaultService.requireUnlocked();
            repository.persist(new MailboxSyncBatch(
                    target.appUserId(),
                    target.mailboxId(),
                    cursor,
                    syncedAt,
                    labels,
                    messages,
                    deletedMessageIds));
        } finally {
            destroyPayloads(messages);
        }
    }

    private static void destroyPayloads(List<CachedMessage> messages) {
        for (CachedMessage message : messages) {
            if (message.encryptedPayload() != null) message.encryptedPayload().destroy();
            if (message.encryptedSnippet() != null) message.encryptedSnippet().destroy();
        }
    }

    private static String safeGmailErrorCode(GmailApiException exception) {
        return "gmail_http_" + exception.getHttpStatus() + "_" + exception.getGoogleErrorCode();
    }

    private static String safeRuntimeErrorCode(RuntimeException exception) {
        Throwable root = exception;
        for (int depth = 0; depth < 4 && root.getCause() != null; depth++) {
            root = root.getCause();
        }
        String type = safeDiagnosticPart(root.getClass().getSimpleName());
        String location = "unknown";
        StackTraceElement[] frames = root.getStackTrace();
        if (frames.length > 0) {
            StackTraceElement origin = frames[0];
            String className = origin.getClassName();
            String simpleClassName = className.substring(className.lastIndexOf('.') + 1);
            location = safeDiagnosticPart(simpleClassName) + "_" + safeDiagnosticPart(origin.getMethodName());
            if (origin.getLineNumber() > 0) location += "_" + origin.getLineNumber();
        }
        String code = "sync_exception_" + type + "_" + location;
        return code.length() <= 160 ? code : "sync_exception_" + type;
    }

    private static String safeDiagnosticPart(String value) {
        String safe = value == null ? "" : value.replaceAll("[^A-Za-z0-9_]", "_");
        if (safe.isEmpty()) return "unknown";
        return safe.length() <= 48 ? safe : safe.substring(0, 48);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static String nullableText(JsonNode node, String field) {
        return text(node, field);
    }

    public record MailboxSyncResult(UUID mailboxId, boolean success, String errorCode, Instant syncedAt) {
        static MailboxSyncResult success(UUID mailboxId, Instant syncedAt) {
            return new MailboxSyncResult(mailboxId, true, null, syncedAt);
        }

        static MailboxSyncResult failure(UUID mailboxId, String errorCode) {
            return new MailboxSyncResult(mailboxId, false, errorCode, null);
        }
    }

    public record ReportMessageDescriptor(String gmailMessageId, String gmailThreadId) { }

    public static final class ReportWindowTooLargeException extends RuntimeException {
        public ReportWindowTooLargeException() {
            super("The report date range contains too many messages. Narrow the date range and try again.");
        }
    }

    public static final class SyncInProgressException extends RuntimeException {
        public SyncInProgressException() {
            super("A mailbox sync is already running.");
        }
    }

    private record MessageDescriptor(String messageId, String threadId) { }

    private record Sender(String name, String email) { }

    private record MessageBodies(String text, String html, String preferredMimeType) { }

    private record EncodedBodyPart(String mimeType, String encodedData) { }

    private record BodyExtraction(MessageBodies bodies, BodyUnavailableReason unavailableReason) { }

    private record FetchedMessages(List<CachedMessage> messages, Set<String> deletedMessageIds) { }

    private static final class SyncProgress {
        private boolean pageDataCommitted;

        private void recordPageDataCommit() {
            pageDataCommitted = true;
        }

        private boolean hasCommittedPageData() {
            return pageDataCommitted;
        }
    }

    private static List<String> metadataHeaders() {
        return List.of("From", "Subject", "Date", "To", "Cc", "Bcc", "Reply-To", "Message-ID");
    }

    private void persistPartialBatch(
            SyncTarget target,
            Instant syncedAt,
            List<GmailLabelMetadata> labels,
            List<CachedMessage> messages,
            Set<String> deletedMessageIds,
            SyncProgress progress) {
        try {
            vaultService.requireUnlocked();
            repository.persistPartial(new MailboxSyncBatch(
                    target.appUserId(),
                    target.mailboxId(),
                    target.historyCursor(),
                    syncedAt,
                    labels,
                    messages,
                    deletedMessageIds));
            if (!messages.isEmpty() || !deletedMessageIds.isEmpty()) progress.recordPageDataCommit();
        } finally {
            destroyPayloads(messages);
        }
    }

    private static final class BodyBudget {
        private final long batchLimitBytes;
        private final Map<String, Long> threadBytes;
        private final Set<String> exhaustedThreads;
        private final int maxTrackedThreads;
        private long totalBytes;
        private boolean syncLimitReached;

        private BodyBudget(long batchLimitBytes) {
            this(batchLimitBytes, new LinkedHashMap<>(), new LinkedHashSet<>(), Integer.MAX_VALUE);
        }

        private BodyBudget(
                long batchLimitBytes,
                Map<String, Long> threadBytes,
                Set<String> exhaustedThreads,
                int maxTrackedThreads) {
            this.batchLimitBytes = batchLimitBytes;
            this.threadBytes = threadBytes;
            this.exhaustedThreads = exhaustedThreads;
            this.maxTrackedThreads = maxTrackedThreads;
        }

        private BodyUnavailableReason check(String threadId, long messageBytes) {
            if (messageBytes > MAX_MESSAGE_BODY_BYTES) return BodyUnavailableReason.MESSAGE_TOO_LARGE;
            if (syncLimitReached || totalBytes + messageBytes > batchLimitBytes) {
                return BodyUnavailableReason.SYNC_LIMIT;
            }
            long currentThreadBytes = threadBytes.getOrDefault(threadId, 0L);
            if (!threadBytes.containsKey(threadId) && threadBytes.size() >= maxTrackedThreads) {
                return BodyUnavailableReason.SYNC_LIMIT;
            }
            if (exhaustedThreads.contains(threadId) || currentThreadBytes + messageBytes > MAX_THREAD_BODY_BYTES) {
                return BodyUnavailableReason.THREAD_LIMIT;
            }
            return null;
        }

        private void record(String threadId, long messageBytes) {
            totalBytes += messageBytes;
            threadBytes.merge(threadId, messageBytes, Long::sum);
        }

        private void noteExceeded(String threadId, BodyUnavailableReason reason) {
            if (reason == BodyUnavailableReason.THREAD_LIMIT) exhaustedThreads.add(threadId);
            if (reason == BodyUnavailableReason.SYNC_LIMIT) syncLimitReached = true;
        }

        private boolean threadLimitReached(String threadId) {
            return exhaustedThreads.contains(threadId);
        }

        private boolean syncLimitReached() {
            return syncLimitReached;
        }
    }
}

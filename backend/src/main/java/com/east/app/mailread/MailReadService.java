package com.east.app.mailread;

import com.east.app.auth.GoogleAccessTokenService;
import com.east.app.attachments.AttachmentFilenameSanitizer;
import com.east.app.gmail.GmailApiClient;
import com.east.app.gmail.GmailApiClient.GmailApiException;
import com.east.app.gmail.GmailCacheAad;
import com.east.app.gmail.GmailRequestGuard;
import com.east.app.gmail.GmailSyncService;
import com.east.app.mailread.MailReadModels.AttachmentMetadataResponse;
import com.east.app.mailread.MailReadModels.GmailLabelResponse;
import com.east.app.mailread.MailReadModels.MailboxLabelResponse;
import com.east.app.mailread.MailReadModels.MailboxIdentity;
import com.east.app.mailread.MailReadModels.SenderResponse;
import com.east.app.mailread.MailReadModels.ThreadDetailResponse;
import com.east.app.mailread.MailReadModels.ThreadMessageResponse;
import com.east.app.mailread.MailReadModels.ThreadPageResponse;
import com.east.app.mailread.MailReadModels.ThreadSummaryResponse;
import com.east.app.mailread.MailReadRepository.AttachmentRow;
import com.east.app.mailread.MailReadRepository.GmailLabelMetadata;
import com.east.app.mailread.MailReadRepository.LabelRow;
import com.east.app.mailread.MailReadRepository.MailboxRow;
import com.east.app.mailread.MailReadRepository.MailboxLabelRow;
import com.east.app.mailread.MailReadRepository.MessageRow;
import com.east.app.mailread.MailReadRepository.SearchMessageMetadata;
import com.east.app.mailread.MailReadRepository.SearchThreadMetadata;
import com.east.app.mailread.MailReadRepository.ThreadOwner;
import com.east.app.mailread.MailReadRepository.ThreadRow;
import com.east.app.vault.EncryptedPayload;
import com.east.app.vault.VaultService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MailReadService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_SEARCH_LENGTH = 500;
    private static final int MAX_CURSORS = 500;
    private static final long CURSOR_LIFETIME_SECONDS = 15 * 60;
    private static final List<String> METADATA_HEADERS = List.of("From", "Subject", "Date");
    private static final Pattern FROM_ADDRESS = Pattern.compile("^(.*?)\\s*<([^<>]+)>\\s*$");
    private static final Set<String> FOLDERS = Set.of(
            "INBOX", "STARRED", "SNOOZED", "SENT", "DRAFTS", "ALL_MAIL", "SPAM", "TRASH");
    private static final Set<String> CATEGORIES = Set.of("PRIMARY", "SOCIAL", "PROMOTIONS", "UPDATES", "FORUMS");

    private final MailReadRepository repository;
    private final GmailApiClient gmailApiClient;
    private final GoogleAccessTokenService accessTokenService;
    private final GmailSyncService syncService;
    private final VaultService vaultService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<String, PageCursor> cursors = new ConcurrentHashMap<>();

    public MailReadService(
            MailReadRepository repository,
            GmailApiClient gmailApiClient,
            GoogleAccessTokenService accessTokenService,
            GmailSyncService syncService,
            VaultService vaultService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = repository;
        this.gmailApiClient = gmailApiClient;
        this.accessTokenService = accessTokenService;
        this.syncService = syncService;
        this.vaultService = vaultService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public ThreadPageResponse listThreads(UUID appUserId, ThreadListQuery request) {
        vaultService.requireUnlocked();
        ThreadListQuery normalized = validate(request);
        List<MailboxRow> mailboxes = resolveMailboxes(appUserId, normalized.mailboxId());
        List<UUID> mailboxIds = mailboxes.stream().map(MailboxRow::id).toList();
        RequestKey key = RequestKey.from(normalized, mailboxIds);
        // Folder/category browsing must not stop at the bounded initial-sync cache. Page
        // metadata from Gmail on demand; full message bodies are still fetched only when
        // the user opens a conversation.
        return searchThreads(appUserId, mailboxes, normalized, key);
    }

    public ThreadDetailResponse getThread(UUID appUserId, UUID threadId) {
        vaultService.requireUnlocked();
        ThreadOwner owner = repository.findOwnedThread(appUserId, threadId).orElseThrow(MailReadService::threadNotFound);
        List<MessageRow> messages = repository.findMessages(owner.id());
        String accessToken = accessTokenService.getValidAccessToken(appUserId, owner.mailboxId());
        vaultService.requireUnlocked();
        JsonNode metadataThread;
        try {
            metadataThread = GmailRequestGuard.call(
                    accessTokenService,
                    appUserId,
                    owner.mailboxId(),
                    accessToken,
                    () -> gmailApiClient.getThread(
                            accessToken, owner.gmailThreadId(), "metadata", METADATA_HEADERS));
        } catch (GmailApiException exception) {
            if (exception.getHttpStatus() == 404) throw threadNotFound();
            throw exception;
        }
        JsonNode remoteMessages = metadataThread.path("messages");
        if (!remoteMessages.isArray() || remoteMessages.isEmpty()) throw threadNotFound();
        Set<String> remoteMessageIds = new HashSet<>();
        for (JsonNode remoteMessage : remoteMessages) {
            String remoteMessageId = text(remoteMessage, "id");
            if (StringUtils.hasText(remoteMessageId)) remoteMessageIds.add(remoteMessageId);
        }
        Set<String> cachedMessageIds = new HashSet<>();
        messages.forEach(message -> cachedMessageIds.add(message.gmailMessageId()));
        boolean partialThread = !remoteMessageIds.equals(cachedMessageIds);
        if (partialThread || messages.stream().anyMatch(message -> !hasUnexpiredBody(message, clock.instant()))) {
            try {
                syncService.loadThreadOnDemand(appUserId, owner.mailboxId(), owner.gmailThreadId());
            } catch (GmailApiException exception) {
                if (exception.getHttpStatus() == 404) throw threadNotFound();
                throw exception;
            }
            messages = repository.findMessages(owner.id());
        }

        List<GmailLabelResponse> labels = repository.findLabelsForThread(owner.id()).stream()
                .map(MailReadService::toLabelResponse)
                .toList();
        List<ThreadMessageResponse> messageDetails = new ArrayList<>(messages.size());
        List<AttachmentMetadataResponse> allAttachments = new ArrayList<>();
        for (MessageRow message : messages) {
            List<AttachmentMetadataResponse> attachments = repository.findAttachments(message.id()).stream()
                    .map(MailReadService::toAttachmentResponse)
                    .toList();
            allAttachments.addAll(attachments);
            messageDetails.add(toMessageDetail(appUserId, owner, message, attachments));
        }
        return new ThreadDetailResponse(
                owner.id(),
                owner.mailboxId(),
                owner.mailbox().identity(),
                owner.subject(),
                labels,
                messageDetails,
                allAttachments);
    }

    public List<MailboxLabelResponse> getLabels(UUID appUserId, UUID mailboxId) {
        vaultService.requireUnlocked();
        MailboxRow mailbox = repository.findOwnedMailbox(appUserId, mailboxId)
                .orElseThrow(MailReadService::mailboxNotFound);
        String accessToken = accessTokenService.getValidAccessToken(appUserId, mailbox.id());
        vaultService.requireUnlocked();
        List<GmailLabelMetadata> labels = parseLabels(GmailRequestGuard.call(
                accessTokenService,
                appUserId,
                mailbox.id(),
                accessToken,
                () -> gmailApiClient.listLabels(accessToken)));
        vaultService.requireUnlocked();
        repository.replaceMailboxLabels(mailboxId, labels);
        return repository.findMailboxLabelCatalog(mailboxId).stream()
                .map(MailReadService::toMailboxLabelResponse)
                .toList();
    }

    private ThreadPageResponse searchThreads(
            UUID appUserId,
            List<MailboxRow> mailboxes,
            ThreadListQuery request,
            RequestKey key) {
        SearchPageCursor state;
        if (request.pageToken() == null) {
            state = createSearchCursor(appUserId, mailboxes, request, key);
        } else {
            PageCursor cursor = consumeCursor(request.pageToken(), appUserId, key);
            if (!(cursor instanceof SearchPageCursor searchCursor)) throw invalidCursor();
            state = searchCursor;
        }

        try {
            List<SearchCandidate> candidates;
            while (true) {
                for (SearchMailboxCursor mailbox : state.mailboxes) {
                    fillSearchMailbox(appUserId, mailbox, request.pageSize());
                }
                candidates = materializeSearchCandidates(appUserId, state);
                if (candidates.size() >= request.pageSize()
                        || state.mailboxes.stream().allMatch(SearchMailboxCursor::exhausted)) {
                    break;
                }
                boolean canContinue = state.mailboxes.stream().anyMatch(mailbox -> !mailbox.exhausted());
                if (!canContinue) break;
            }

            candidates.sort(Comparator
                    .comparing((SearchCandidate candidate) -> candidate.summary().latestMessageAt()).reversed()
                    .thenComparing(candidate -> candidate.summary().mailboxId())
                    .thenComparing(candidate -> candidate.summary().id()));
            List<SearchCandidate> selected = candidates.stream().limit(request.pageSize()).toList();
            for (SearchCandidate candidate : selected) {
                candidate.mailboxCursor().queue.removeIf(ref -> ref.gmailThreadId.equals(candidate.gmailThreadId()));
            }
            boolean more = state.mailboxes.stream()
                    .anyMatch(mailbox -> !mailbox.queue.isEmpty() || !mailbox.exhausted());
            String next = more ? storeCursor(state.withExpiry(expiresAt())) : null;
            return new ThreadPageResponse(
                    selected.stream().map(SearchCandidate::summary).toList(),
                    next,
                    asEstimate(state.totalEstimate()));
        } catch (RuntimeException exception) {
            if (request.pageToken() != null) restoreCursor(request.pageToken(), state);
            throw exception;
        }
    }

    private SearchPageCursor createSearchCursor(
            UUID appUserId, List<MailboxRow> mailboxes, ThreadListQuery request, RequestKey key) {
        List<SearchMailboxCursor> cursors = new ArrayList<>(mailboxes.size());
        for (MailboxRow mailbox : mailboxes) {
            String accessToken = accessTokenService.getValidAccessToken(appUserId, mailbox.id());
            vaultService.requireUnlocked();
            repository.replaceMailboxLabels(mailbox.id(), parseLabels(GmailRequestGuard.call(
                    accessTokenService,
                    appUserId,
                    mailbox.id(),
                    accessToken,
                    () -> gmailApiClient.listLabels(accessToken))));
            String gmailLabelId = null;
            boolean unsupportedLabel = false;
            if (request.labelId() != null) {
                LabelRow label = repository.findOwnedLabel(appUserId, mailbox.id(), request.labelId()).orElse(null);
                if (label == null) unsupportedLabel = true;
                else gmailLabelId = label.gmailLabelId();
            }
            cursors.add(new SearchMailboxCursor(
                    mailbox,
                    buildGmailQuery(request),
                    gmailLabelId,
                    request.folder() != null && (request.folder().equals("SPAM") || request.folder().equals("TRASH")),
                    unsupportedLabel));
        }
        return new SearchPageCursor(appUserId, key, expiresAt(), cursors);
    }

    private void fillSearchMailbox(UUID appUserId, SearchMailboxCursor mailbox, int pageSize) {
        while (!mailbox.exhausted && mailbox.queue.size() < pageSize) {
            vaultService.requireUnlocked();
            String accessToken = accessTokenService.getValidAccessToken(appUserId, mailbox.mailbox.id());
            int desired = Math.max(1, Math.min(MAX_PAGE_SIZE, pageSize - mailbox.queue.size()));
            List<String> labelIds = mailbox.gmailLabelId == null ? null : List.of(mailbox.gmailLabelId);
            JsonNode page = GmailRequestGuard.call(
                    accessTokenService,
                    appUserId,
                    mailbox.mailbox.id(),
                    accessToken,
                    () -> gmailApiClient.listThreads(
                            accessToken,
                            mailbox.gmailQuery,
                            desired,
                            mailbox.pageToken,
                            labelIds,
                            mailbox.includeSpamTrash));
            JsonNode threads = page.path("threads");
            if (threads.isArray()) {
                for (JsonNode thread : threads) {
                    String id = text(thread, "id");
                    if (StringUtils.hasText(id)
                            && mailbox.queue.stream().noneMatch(ref -> ref.gmailThreadId.equals(id))) {
                        mailbox.queue.add(new SearchThreadRef(id, true));
                    }
                }
            }
            String next = text(page, "nextPageToken");
            mailbox.pageToken = next;
            mailbox.exhausted = !StringUtils.hasText(next);
            JsonNode estimate = page.path("resultSizeEstimate");
            if (estimate.isNumber()) {
                mailbox.resultSizeEstimate = Math.max(mailbox.resultSizeEstimate, Math.max(0, estimate.asLong()));
            } else {
                mailbox.resultSizeEstimate = Math.max(mailbox.resultSizeEstimate, mailbox.queue.size());
            }
            if ((!threads.isArray() || threads.isEmpty()) && mailbox.exhausted) break;
        }
    }

    private List<SearchCandidate> materializeSearchCandidates(UUID appUserId, SearchPageCursor state) {
        List<SearchCandidate> candidates = new ArrayList<>();
        for (SearchMailboxCursor mailbox : state.mailboxes) {
            var iterator = mailbox.queue.iterator();
            while (iterator.hasNext()) {
                SearchThreadRef ref = iterator.next();
                if (ref.needsMetadata) {
                    try {
                        vaultService.requireUnlocked();
                        String token = accessTokenService.getValidAccessToken(appUserId, mailbox.mailbox.id());
                        JsonNode thread = GmailRequestGuard.call(
                                accessTokenService,
                                appUserId,
                                mailbox.mailbox.id(),
                                token,
                                () -> gmailApiClient.getThread(
                                        token, ref.gmailThreadId, "metadata", METADATA_HEADERS));
                        SearchThreadMetadata metadata = parseSearchThread(ref.gmailThreadId, thread);
                        repository.upsertSearchThreadMetadata(mailbox.mailbox.id(), metadata);
                        ref.needsMetadata = false;
                    } catch (GmailApiException exception) {
                        if (exception.getHttpStatus() == 404) {
                            iterator.remove();
                            continue;
                        }
                        throw exception;
                    }
                }
                ThreadRow row = repository.findOwnedThreadSummaryByGmailId(
                        appUserId, mailbox.mailbox.id(), ref.gmailThreadId).orElse(null);
                if (row == null) {
                    iterator.remove();
                    continue;
                }
                List<LabelRow> labels = repository.findLabelsForThread(row.id());
                candidates.add(new SearchCandidate(mailbox, ref.gmailThreadId, toSummary(appUserId, row, labels)));
            }
        }
        return candidates;
    }

    private SearchThreadMetadata parseSearchThread(String requestedGmailThreadId, JsonNode thread) {
        JsonNode messages = thread.path("messages");
        if (!messages.isArray() || messages.isEmpty()) throw upstreamResponseInvalid();
        List<SearchMessageMetadata> parsedMessages = new ArrayList<>();
        String threadId = text(thread, "id");
        if (!StringUtils.hasText(threadId)) threadId = requestedGmailThreadId;
        String subject = "";
        Instant latest = null;
        for (JsonNode message : messages) {
            String gmailMessageId = text(message, "id");
            if (!StringUtils.hasText(gmailMessageId)) continue;
            Map<String, String> headers = headers(message.path("payload"));
            Instant sentAt = parseMessageTime(message, headers.get("date"));
            Sender sender = parseSender(headers.get("from"));
            parsedMessages.add(new SearchMessageMetadata(
                    gmailMessageId,
                    sender.email() == null ? "" : sender.email(),
                    sender.name(),
                    sentAt,
                    readLabelIds(message.path("labelIds"))));
            if (latest == null || sentAt.isAfter(latest)) {
                latest = sentAt;
                subject = headers.getOrDefault("subject", "");
            }
        }
        if (parsedMessages.isEmpty() || latest == null) throw upstreamResponseInvalid();
        return new SearchThreadMetadata(threadId, subject, latest, parsedMessages);
    }

    private ThreadSummaryResponse toSummary(UUID appUserId, ThreadRow row, List<LabelRow> labelRows) {
        String snippet = null;
        Instant now = clock.instant();
        if (row.snippetNonce() != null && row.snippetCiphertext() != null
                && row.snippetExpiresAt() != null && row.snippetExpiresAt().isAfter(now)) {
            EncryptedPayload encrypted = new EncryptedPayload(row.snippetNonce(), row.snippetCiphertext());
            byte[] plaintext = null;
            try {
                plaintext = vaultService.decryptLocal(
                        encrypted,
                        GmailCacheAad.threadSnippet(appUserId, row.mailboxId(), row.gmailThreadId()));
                snippet = new String(plaintext, StandardCharsets.UTF_8);
            } finally {
                encrypted.destroy();
                wipe(plaintext);
            }
        }
        List<GmailLabelResponse> labels = labelRows.stream().map(MailReadService::toLabelResponse).toList();
        String category = labelRows.stream()
                .map(LabelRow::gmailLabelId)
                .map(MailReadService::categoryFromLabelId)
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);
        return new ThreadSummaryResponse(
                row.id(),
                row.mailboxId(),
                row.mailbox().identity(),
                row.subject(),
                snippet,
                new SenderResponse(row.senderName() == null ? "" : row.senderName(), row.senderEmail()),
                row.latestMessageAt(),
                row.unread(),
                row.starred(),
                labels,
                category,
                row.messageCount(),
                row.attachmentsAvailable());
    }

    private ThreadMessageResponse toMessageDetail(
            UUID appUserId,
            ThreadOwner owner,
            MessageRow message,
            List<AttachmentMetadataResponse> attachments) {
        String bodyText = null;
        String bodyHtml = null;
        String bodyUnavailableReason = null;
        Map<String, String> headers = Map.of();
        Instant now = clock.instant();
        if (hasUnexpiredBody(message, now)) {
            bodyUnavailableReason = bodyUnavailableReason(message.bodyMimeType());
            EncryptedPayload encrypted = new EncryptedPayload(message.bodyNonce(), message.bodyCiphertext());
            byte[] plaintext = null;
            try {
                plaintext = vaultService.decryptLocal(
                        encrypted,
                        GmailCacheAad.message(
                                appUserId,
                                owner.mailboxId(),
                                owner.gmailThreadId(),
                                message.gmailMessageId()));
                JsonNode payload = objectMapper.readTree(plaintext);
                bodyText = nullableText(payload, "bodyText");
                bodyHtml = MailHtmlSanitizer.sanitize(nullableText(payload, "bodyHtml"));
                if (bodyText == null) bodyText = MailHtmlSanitizer.toText(bodyHtml);
                headers = stringMap(payload.path("headers"));
            } catch (IOException exception) {
                throw new IllegalStateException("The cached message content was invalid.");
            } finally {
                encrypted.destroy();
                wipe(plaintext);
            }
        }
        Map<String, String> selectedHeaders = headers;
        return new ThreadMessageResponse(
                message.id(),
                new SenderResponse(
                        message.senderName() == null ? "" : message.senderName(),
                        message.senderEmail()),
                headerList(selectedHeaders.get("to")),
                headerList(selectedHeaders.get("cc")),
                headerList(selectedHeaders.get("bcc")),
                selectedHeaders.get("replyTo"),
                selectedHeaders.get("messageId"),
                message.sentAt(),
                bodyText,
                bodyHtml,
                bodyUnavailableReason,
                headers,
                attachments);
    }

    private static String bodyUnavailableReason(String bodyMimeType) {
        if (bodyMimeType == null) return null;
        return switch (bodyMimeType) {
            case "east-internal:MESSAGE_TOO_LARGE" -> "MESSAGE_TOO_LARGE";
            case "east-internal:THREAD_LIMIT" -> "THREAD_LIMIT";
            case "east-internal:SYNC_LIMIT", "east-internal:SYNC_LIMIT_ON_DEMAND" -> "SYNC_LIMIT";
            case "east-internal:ON_DEMAND" -> "ON_DEMAND";
            default -> null;
        };
    }

    private static List<GmailLabelMetadata> parseLabels(JsonNode response) {
        JsonNode labels = response.path("labels");
        if (!labels.isArray()) throw upstreamResponseInvalid();
        List<GmailLabelMetadata> result = new ArrayList<>();
        for (JsonNode label : labels) {
            String id = text(label, "id");
            String name = text(label, "name");
            if (!StringUtils.hasText(id) || !StringUtils.hasText(name)) continue;
            String type = "SYSTEM".equalsIgnoreCase(text(label, "type")) ? "SYSTEM" : "USER";
            result.add(new GmailLabelMetadata(id, name, type));
        }
        return List.copyOf(result);
    }

    private ThreadListQuery validate(ThreadListQuery request) {
        if (request == null) throw invalidRequest();
        String folder = request.folder() == null ? null : request.folder().trim().toUpperCase(Locale.ROOT);
        if (folder != null && !FOLDERS.contains(folder)) throw invalidRequest();
        String category = request.category() == null ? null : request.category().trim().toUpperCase(Locale.ROOT);
        if (category != null && !CATEGORIES.contains(category)) throw invalidRequest();
        String query = request.query() == null ? null : request.query().trim();
        if (query != null && query.isEmpty()) query = null;
        if (query != null && query.length() > MAX_SEARCH_LENGTH) throw invalidRequest();
        if (request.pageSize() < 1 || request.pageSize() > MAX_PAGE_SIZE) throw invalidRequest();
        String pageToken = request.pageToken() == null || request.pageToken().isBlank()
                ? null
                : request.pageToken().trim();
        if (pageToken != null && pageToken.length() > 128) throw invalidCursor();
        return new ThreadListQuery(
                request.mailboxId(), folder, category, query, request.unread(), request.starred(),
                request.labelId(), pageToken, request.pageSize());
    }

    private List<MailboxRow> resolveMailboxes(UUID appUserId, UUID mailboxId) {
        if (mailboxId == null) return repository.findOwnedMailboxes(appUserId);
        return List.of(repository.findOwnedMailbox(appUserId, mailboxId)
                .orElseThrow(MailReadService::mailboxNotFound));
    }

    private String buildGmailQuery(ThreadListQuery request) {
        List<String> clauses = new ArrayList<>();
        if (request.query() != null) clauses.add("(" + request.query() + ")");
        if (request.folder() != null) {
            String folderClause = switch (request.folder()) {
                case "INBOX" -> "in:inbox";
                case "STARRED" -> "is:starred";
                case "SNOOZED" -> "is:snoozed";
                case "SENT" -> "in:sent";
                case "DRAFTS" -> "in:drafts";
                case "SPAM" -> "in:spam";
                case "TRASH" -> "in:trash";
                default -> null;
            };
            if (folderClause != null) clauses.add(folderClause);
        }
        if (request.category() != null) {
            clauses.add("category:" + request.category().toLowerCase(Locale.ROOT));
        }
        if (Boolean.TRUE.equals(request.unread())) clauses.add("is:unread");
        else if (Boolean.FALSE.equals(request.unread())) clauses.add("-is:unread");
        if (Boolean.TRUE.equals(request.starred())) clauses.add("is:starred");
        else if (Boolean.FALSE.equals(request.starred())) clauses.add("-is:starred");
        return String.join(" ", clauses);
    }

    private static boolean hasUnexpiredBody(MessageRow message, Instant now) {
        return message.bodyNonce() != null
                && message.bodyCiphertext() != null
                && message.bodyExpiresAt() != null
                && message.bodyExpiresAt().isAfter(now);
    }

    private static GmailLabelResponse toLabelResponse(LabelRow row) {
        return new GmailLabelResponse(row.id(), row.name(), null);
    }

    private static MailboxLabelResponse toMailboxLabelResponse(MailboxLabelRow row) {
        return new MailboxLabelResponse(row.id(), row.name(), null, row.type());
    }

    private static AttachmentMetadataResponse toAttachmentResponse(AttachmentRow row) {
        return new AttachmentMetadataResponse(
                row.id(), AttachmentFilenameSanitizer.sanitize(row.filename()), row.mimeType(), row.byteSize());
    }

    private static String categoryFromLabelId(String labelId) {
        return switch (labelId) {
            case "CATEGORY_PERSONAL" -> "PRIMARY";
            case "CATEGORY_SOCIAL" -> "SOCIAL";
            case "CATEGORY_PROMOTIONS" -> "PROMOTIONS";
            case "CATEGORY_UPDATES" -> "UPDATES";
            case "CATEGORY_FORUMS" -> "FORUMS";
            default -> null;
        };
    }

    private static Map<String, String> headers(JsonNode payload) {
        Map<String, String> result = new LinkedHashMap<>();
        JsonNode entries = payload.path("headers");
        if (!entries.isArray()) return result;
        for (JsonNode entry : entries) {
            String name = text(entry, "name");
            String value = text(entry, "value");
            if (name != null && value != null) result.putIfAbsent(name.toLowerCase(Locale.ROOT), value);
        }
        return result;
    }

    private static Sender parseSender(String header) {
        if (!StringUtils.hasText(header)) return new Sender("", "");
        Matcher matcher = FROM_ADDRESS.matcher(header.trim());
        if (matcher.matches()) {
            String name = matcher.group(1).trim().replaceAll("^\\\"|\\\"$", "");
            return new Sender(name, matcher.group(2).trim());
        }
        String value = header.trim();
        return value.contains("@") ? new Sender(value, value) : new Sender(value, "");
    }

    private static Instant parseMessageTime(JsonNode message, String headerDate) {
        JsonNode internalDate = message.path("internalDate");
        if (internalDate.isNumber() && internalDate.asLong() > 0) return Instant.ofEpochMilli(internalDate.asLong());
        if (internalDate.isTextual() && StringUtils.hasText(internalDate.asText())) {
            try {
                long epochMillis = Long.parseLong(internalDate.asText());
                if (epochMillis > 0) return Instant.ofEpochMilli(epochMillis);
            } catch (NumberFormatException ignored) {
                // Fall back to the message Date header if Gmail's epoch timestamp is malformed.
            }
        }
        if (StringUtils.hasText(headerDate)) {
            try {
                return ZonedDateTime.parse(headerDate, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            } catch (DateTimeParseException ignored) {
                // Gmail normally includes internalDate; unparseable text is not stored as a timestamp.
            }
        }
        throw upstreamResponseInvalid();
    }

    private static List<String> readLabelIds(JsonNode labels) {
        if (!labels.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode label : labels) {
            if (label.isTextual() && StringUtils.hasText(label.asText())) result.add(label.asText());
        }
        return List.copyOf(result);
    }

    private static Map<String, String> stringMap(JsonNode node) {
        if (!node.isObject()) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue().isTextual()) result.put(entry.getKey(), entry.getValue().asText());
        });
        return Map.copyOf(result);
    }

    private static List<String> headerList(String value) {
        return StringUtils.hasText(value) ? List.of(value) : List.of();
    }

    private static String nullableText(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private synchronized PageCursor consumeCursor(String token, UUID appUserId, RequestKey key) {
        purgeExpiredCursors();
        PageCursor cursor = cursors.remove(token);
        if (cursor == null || !cursor.appUserId().equals(appUserId)
                || !cursor.key().equals(key) || !cursor.expiresAt().isAfter(clock.instant())) {
            throw invalidCursor();
        }
        return cursor;
    }

    private synchronized String storeCursor(PageCursor cursor) {
        purgeExpiredCursors();
        while (cursors.size() >= MAX_CURSORS) {
            String oldestKey = cursors.keySet().stream().findFirst().orElse(null);
            if (oldestKey == null) break;
            cursors.remove(oldestKey);
        }
        String token = UUID.randomUUID().toString();
        cursors.put(token, cursor);
        return token;
    }

    private synchronized void restoreCursor(String token, PageCursor cursor) {
        purgeExpiredCursors();
        if (cursor.expiresAt().isAfter(clock.instant())) cursors.putIfAbsent(token, cursor);
    }

    private void purgeExpiredCursors() {
        Instant now = clock.instant();
        cursors.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private Instant expiresAt() {
        return clock.instant().plusSeconds(CURSOR_LIFETIME_SECONDS);
    }

    private static int asEstimate(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, value));
    }

    private static MailReadException invalidRequest() {
        return new MailReadException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The conversation request is invalid.");
    }

    private static MailReadException invalidCursor() {
        return new MailReadException(HttpStatus.BAD_REQUEST, "INVALID_PAGE_TOKEN", "This page token is invalid or expired. Refresh the conversation list.");
    }

    private static MailReadException mailboxNotFound() {
        return new MailReadException(HttpStatus.NOT_FOUND, "MAILBOX_NOT_FOUND", "The requested mailbox was not found.");
    }

    private static MailReadException threadNotFound() {
        return new MailReadException(HttpStatus.NOT_FOUND, "THREAD_NOT_FOUND", "The requested conversation was not found.");
    }

    private static MailReadException upstreamResponseInvalid() {
        return new MailReadException(HttpStatus.BAD_GATEWAY, "GMAIL_RESPONSE_INVALID", "Gmail returned an invalid conversation response.");
    }

    private static void wipe(byte[] value) {
        if (value != null) Arrays.fill(value, (byte) 0);
    }

    public record ThreadListQuery(
            UUID mailboxId,
            String folder,
            String category,
            String query,
            Boolean unread,
            Boolean starred,
            UUID labelId,
            String pageToken,
            int pageSize) { }

    private record Sender(String name, String email) { }

    private record RequestKey(
            UUID mailboxId,
            String folder,
            String category,
            String query,
            Boolean unread,
            Boolean starred,
            UUID labelId,
            int pageSize,
            List<UUID> scopedMailboxes) {
        static RequestKey from(ThreadListQuery request, List<UUID> mailboxIds) {
            return new RequestKey(
                    request.mailboxId(), request.folder(), request.category(), request.query(),
                    request.unread(), request.starred(), request.labelId(), request.pageSize(), mailboxIds);
        }
    }

    private sealed interface PageCursor permits LocalPageCursor, SearchPageCursor {
        UUID appUserId();
        RequestKey key();
        Instant expiresAt();
    }

    private record LocalPageCursor(UUID appUserId, RequestKey key, long offset, Instant expiresAt)
            implements PageCursor { }

    private static final class SearchPageCursor implements PageCursor {
        private final UUID appUserId;
        private final RequestKey key;
        private final List<SearchMailboxCursor> mailboxes;
        private final Instant expiresAt;

        private SearchPageCursor(
                UUID appUserId, RequestKey key, Instant expiresAt, List<SearchMailboxCursor> mailboxes) {
            this.appUserId = appUserId;
            this.key = key;
            this.expiresAt = expiresAt;
            this.mailboxes = mailboxes;
        }

        private SearchPageCursor withExpiry(Instant nextExpiry) {
            return new SearchPageCursor(appUserId, key, nextExpiry, mailboxes);
        }

        private long totalEstimate() {
            return mailboxes.stream().mapToLong(mailbox -> mailbox.resultSizeEstimate).sum();
        }

        @Override public UUID appUserId() { return appUserId; }
        @Override public RequestKey key() { return key; }
        @Override public Instant expiresAt() { return expiresAt; }
    }

    private static final class SearchMailboxCursor {
        private final MailboxRow mailbox;
        private final String gmailQuery;
        private final String gmailLabelId;
        private final boolean includeSpamTrash;
        private final List<SearchThreadRef> queue = new ArrayList<>();
        private String pageToken;
        private boolean exhausted;
        private long resultSizeEstimate;

        private SearchMailboxCursor(
                MailboxRow mailbox,
                String gmailQuery,
                String gmailLabelId,
                boolean includeSpamTrash,
                boolean unsupportedLabel) {
            this.mailbox = mailbox;
            this.gmailQuery = gmailQuery;
            this.gmailLabelId = gmailLabelId;
            this.includeSpamTrash = includeSpamTrash;
            this.exhausted = unsupportedLabel;
        }

        private boolean exhausted() { return exhausted; }
    }

    private static final class SearchThreadRef {
        private final String gmailThreadId;
        private boolean needsMetadata;

        private SearchThreadRef(String gmailThreadId, boolean needsMetadata) {
            this.gmailThreadId = gmailThreadId;
            this.needsMetadata = needsMetadata;
        }
    }

    private record SearchCandidate(
            SearchMailboxCursor mailboxCursor,
            String gmailThreadId,
            ThreadSummaryResponse summary) { }
}

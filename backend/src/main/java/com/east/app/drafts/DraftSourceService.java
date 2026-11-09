package com.east.app.drafts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.east.app.auth.GoogleAccessTokenService;
import com.east.app.gmail.GmailApiClient;
import com.east.app.gmail.GmailCacheAad;
import com.east.app.mailread.MailReadRepository;
import com.east.app.vault.EncryptedPayload;
import com.east.app.vault.VaultCryptoService;
import com.east.app.vault.VaultService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

@Service
final class DraftSourceService {

    private static final int MAX_SOURCE_BODY_BYTES = 1024 * 1024;
    private static final String INLINE_ATTACHMENT_PREFIX = "inline:";
    private static final List<String> SOURCE_METADATA_HEADERS = List.of(
            "From", "Subject", "Date", "To", "Cc", "Bcc", "Reply-To", "Message-ID", "In-Reply-To", "References");
    private static final Pattern EMAIL_EXTRACTOR = Pattern.compile(
            "[A-Za-z0-9_+\\-][A-Za-z0-9._%+\\-]*@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*");
    private static final Pattern MESSAGE_ID = Pattern.compile("<[^<>\\s\\p{Cntrl}]{1,250}>");

    private final MailReadRepository mailReadRepository;
    private final DraftRepository draftRepository;
    private final GoogleAccessTokenService accessTokenService;
    private final GmailApiClient gmailApiClient;
    private final VaultService vaultService;
    private final ObjectMapper objectMapper;
    private final DraftHtmlSanitizer htmlSanitizer;
    private final Clock clock;

    DraftSourceService(
            MailReadRepository mailReadRepository,
            DraftRepository draftRepository,
            GoogleAccessTokenService accessTokenService,
            GmailApiClient gmailApiClient,
            VaultService vaultService,
            ObjectMapper objectMapper,
            DraftHtmlSanitizer htmlSanitizer,
            Clock clock) {
        this.mailReadRepository = mailReadRepository;
        this.draftRepository = draftRepository;
        this.accessTokenService = accessTokenService;
        this.gmailApiClient = gmailApiClient;
        this.vaultService = vaultService;
        this.objectMapper = objectMapper;
        this.htmlSanitizer = htmlSanitizer;
        this.clock = clock;
    }

    DraftSourceContext load(
            UUID appUserId,
            UUID mailboxId,
            UUID sourceThreadId,
            UUID sourceMessageId,
            DraftKind kind) {
        if (kind == DraftKind.NEW || sourceThreadId == null || sourceMessageId == null) {
            throw DraftException.badRequest("Choose a source conversation and message for this draft type.");
        }
        MailReadRepository.ThreadOwner thread = mailReadRepository.findOwnedThread(appUserId, sourceThreadId)
                .orElseThrow(DraftException::notFound);
        if (!thread.mailboxId().equals(mailboxId)) {
            throw DraftException.notFound();
        }
        List<MailReadRepository.MessageRow> threadMessages = mailReadRepository.findMessages(thread.id());
        MailReadRepository.MessageRow message = threadMessages.stream()
                .filter(item -> item.id().equals(sourceMessageId))
                .findFirst()
                .orElse(null);
        for (MailReadRepository.MessageRow threadMessage : threadMessages) {
            if (threadMessage != message) {
                wipe(threadMessage.bodyNonce());
                wipe(threadMessage.bodyCiphertext());
            }
        }
        if (message == null) {
            throw DraftException.notFound();
        }

        String accessToken = null;
        MessageContent content;
        if (hasUsableBodyCache(message)) {
            content = decryptCachedMessage(appUserId, thread, message);
        } else {
            wipe(message.bodyNonce());
            wipe(message.bodyCiphertext());
            accessToken = accessTokenService.getValidAccessToken(appUserId, mailboxId);
            content = fetchMessage(accessToken, message.gmailMessageId(), kind);
        }

        List<DraftMimeBuilder.OutboundAttachment> forwardAttachments = List.of();
        if (kind == DraftKind.FORWARD) {
            List<SourceAttachment> cachedAttachments = draftRepository.findOwnedSourceAttachments(
                            appUserId, mailboxId, sourceThreadId, sourceMessageId).stream()
                    .map(row -> new SourceAttachment(
                            row.gmailAttachmentId(), row.filename(), row.mimeType(), row.byteSize(), null))
                    .toList();
            List<SourceAttachment> attachments = content.attachments().isEmpty()
                    ? cachedAttachments
                    : content.attachments();
            if (!attachments.isEmpty()) {
                if (accessToken == null) {
                    accessToken = accessTokenService.getValidAccessToken(appUserId, mailboxId);
                }
                forwardAttachments = fetchSourceAttachments(
                        accessToken, message.gmailMessageId(), attachments);
            }
        }

        Map<String, String> headers = content.headers();
        String messageId = firstMessageId(headers.get("message-id"));
        if ((kind == DraftKind.REPLY || kind == DraftKind.REPLY_ALL) && messageId == null) {
            DraftService.wipeOutboundAttachments(forwardAttachments);
            throw DraftException.conflict(
                    "The selected message has no usable Message-ID, so Gmail cannot safely thread this reply.");
        }
        String references = combineReferences(headers.get("references"), messageId);
        return new DraftSourceContext(
                sourceThreadId,
                sourceMessageId,
                mailboxId,
                thread.gmailThreadId(),
                message.gmailMessageId(),
                thread.subject(),
                message.senderEmail(),
                message.senderName(),
                message.sentAt(),
                content.bodyText(),
                content.bodyHtml(),
                extractAddresses(headers.get("to")),
                extractAddresses(headers.get("cc")),
                extractAddresses(headers.get("reply-to")),
                messageId,
                references,
                forwardAttachments);
    }

    private boolean hasUsableBodyCache(MailReadRepository.MessageRow message) {
        return message.bodyNonce() != null
                && message.bodyCiphertext() != null
                && message.bodyExpiresAt() != null
                && message.bodyExpiresAt().isAfter(clock.instant());
    }

    private MessageContent decryptCachedMessage(
            UUID appUserId,
            MailReadRepository.ThreadOwner thread,
            MailReadRepository.MessageRow message) {
        byte[] nonce = message.bodyNonce().clone();
        byte[] ciphertext = message.bodyCiphertext().clone();
        EncryptedPayload encrypted = new EncryptedPayload(nonce, ciphertext);
        byte[] plaintext = null;
        try {
            plaintext = vaultService.decryptLocal(
                    encrypted,
                    GmailCacheAad.message(
                            appUserId, thread.mailboxId(), thread.gmailThreadId(), message.gmailMessageId()));
            JsonNode cached = objectMapper.readTree(plaintext);
            if (cached.path("schemaVersion").asInt(-1) != 1) {
                throw DraftException.conflict("The source message cache is not supported. Open it in Conversations and retry.");
            }
            return new MessageContent(
                    textOrEmpty(cached.path("bodyText")),
                    htmlSanitizer.sanitize(textOrEmpty(cached.path("bodyHtml"))),
                    stringMap(cached.path("headers")),
                    List.of());
        } catch (VaultCryptoService.InvalidVaultCiphertextException | IOException exception) {
            throw DraftException.conflict("The source message could not be opened. Refresh it in Conversations and retry.");
        } finally {
            encrypted.destroy();
            wipe(nonce);
            wipe(ciphertext);
            wipe(plaintext);
            wipe(message.bodyNonce());
            wipe(message.bodyCiphertext());
        }
    }

    private MessageContent fetchMessage(String accessToken, String gmailMessageId, DraftKind kind) {
        JsonNode message;
        try {
            message = gmailApiClient.getMessage(accessToken, gmailMessageId, "full", null);
        } catch (GmailApiClient.GmailResponseTooLargeException exception) {
            // Large inline-heavy messages can exceed the bounded full-message response even when
            // the user only wants a reply. Fall back to headers; forwarding then uses the
            // attachment metadata retained during structure-only sync.
            try {
                message = gmailApiClient.getMessage(
                        accessToken, gmailMessageId, "metadata", SOURCE_METADATA_HEADERS);
            } catch (GmailApiClient.GmailResponseTooLargeException metadataException) {
                throw DraftException.conflict("The source message exceeds East's local read limit.");
            } catch (GmailApiClient.GmailApiException metadataException) {
                if (metadataException.getHttpStatus() == 404) {
                    throw DraftException.conflict("The source message no longer exists in Gmail.");
                }
                throw DraftException.upstreamFailure();
            }
        } catch (GmailApiClient.GmailApiException exception) {
            if (exception.getHttpStatus() == 404) {
                throw DraftException.conflict("The source message no longer exists in Gmail.");
            }
            throw DraftException.upstreamFailure();
        }
        JsonNode payload = message.path("payload");
        try {
            Map<String, String> headers = headersFromGmail(payload.path("headers"));
            BodyParts bodies = extractBodyParts(payload);
            List<SourceAttachment> attachments = kind == DraftKind.FORWARD
                    ? extractSourceAttachments(payload)
                    : List.of();
            String text = bodies.plainText();
            String html = htmlSanitizer.sanitize(bodies.html());
            if (text.isEmpty() && !html.isEmpty()) {
                text = org.jsoup.Jsoup.parse(html).text();
            }
            return new MessageContent(text, html, headers, attachments);
        } finally {
            // Replies need the message text, but never use source attachment data. Forwards
            // retain only the selected attachment descriptors above. Clear every MIME node
            // before this full Gmail response goes out of scope.
            clearMimeBodyData(payload);
        }
    }

    private static void clearMimeBodyData(JsonNode part) {
        if (!(part instanceof ObjectNode object)) return;
        JsonNode body = object.path("body");
        if (body instanceof ObjectNode bodyObject) bodyObject.remove("data");
        JsonNode children = object.path("parts");
        if (children.isArray()) children.forEach(DraftSourceService::clearMimeBodyData);
    }

    private BodyParts extractBodyParts(JsonNode payload) {
        List<String> plainTextParts = new ArrayList<>();
        List<String> htmlParts = new ArrayList<>();
        collectBodyParts(payload, plainTextParts, htmlParts);
        String plainText = String.join("\n", plainTextParts);
        String html = String.join("<br>", htmlParts);
        if (plainText.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BODY_BYTES
                || html.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BODY_BYTES) {
            throw DraftException.badRequest("The source message is too large to quote in a draft.");
        }
        return new BodyParts(plainText, html);
    }

    private void collectBodyParts(JsonNode part, List<String> plainText, List<String> html) {
        if (!part.path("filename").asText("").isBlank()) {
            return;
        }
        JsonNode children = part.path("parts");
        if (children.isArray()) {
            children.forEach(child -> collectBodyParts(child, plainText, html));
            return;
        }
        String mimeType = part.path("mimeType").asText("").toLowerCase(Locale.ROOT);
        if (!mimeType.equals("text/plain") && !mimeType.equals("text/html")) {
            return;
        }
        JsonNode data = part.path("body").path("data");
        if (!data.isTextual() || data.asText().isEmpty()) {
            return;
        }
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(data.asText());
        } catch (IllegalArgumentException exception) {
            throw DraftException.conflict("The source message content could not be decoded.");
        }
        try {
            if (decoded.length > MAX_SOURCE_BODY_BYTES) {
                throw DraftException.badRequest("The source message is too large to quote in a draft.");
            }
            String value = new String(decoded, StandardCharsets.UTF_8);
            (mimeType.equals("text/plain") ? plainText : html).add(value);
        } finally {
            wipe(decoded);
        }
    }

    private List<DraftMimeBuilder.OutboundAttachment> fetchSourceAttachments(
            String accessToken,
            String gmailMessageId,
            List<SourceAttachment> sourceAttachments) {
        if (sourceAttachments.size() > DraftAttachmentPolicy.MAX_ATTACHMENTS_PER_DRAFT) {
            throw DraftException.badRequest("This message has more attachments than East can stage in one draft.");
        }
        long declaredTotal = 0;
        for (SourceAttachment attachment : sourceAttachments) {
            if (attachment.byteSize() == null) {
                throw DraftException.conflict(
                        "A source attachment's size is unavailable, so East cannot safely stage it for forwarding.");
            }
            if (attachment.byteSize() > DraftAttachmentPolicy.MAX_FILE_BYTES) {
                throw DraftException.badRequest("A source attachment is larger than 10 MiB and cannot be staged.");
            }
            declaredTotal += attachment.byteSize();
        }
        if (declaredTotal > DraftAttachmentPolicy.MAX_TOTAL_BYTES) {
            throw DraftException.badRequest("Source attachments exceed the 16 MiB draft limit.");
        }

        List<DraftMimeBuilder.OutboundAttachment> result = new ArrayList<>(sourceAttachments.size());
        long actualTotal = 0;
        try {
            for (SourceAttachment attachment : sourceAttachments) {
                String filename = DraftAttachmentPolicy.normalizeSourceFilename(attachment.filename());
                String mimeType = DraftAttachmentPolicy.normalizeSourceMimeType(attachment.mimeType());
                byte[] bytes;
                if ((attachment.gmailAttachmentId() == null || attachment.gmailAttachmentId().isBlank())
                        && attachment.inlineData() != null) {
                    try {
                        bytes = Base64.getUrlDecoder().decode(attachment.inlineData());
                    } catch (IllegalArgumentException exception) {
                        throw DraftException.conflict("A source attachment could not be decoded safely.");
                    }
                } else if (attachment.gmailAttachmentId() != null
                        && attachment.gmailAttachmentId().startsWith(INLINE_ATTACHMENT_PREFIX)) {
                    String partId = attachment.gmailAttachmentId().substring(INLINE_ATTACHMENT_PREFIX.length());
                    GmailApiClient.InlineAttachmentPayload inline;
                    try {
                        inline = gmailApiClient.getInlineAttachment(accessToken, gmailMessageId, partId)
                                .orElseThrow(() -> DraftException.conflict(
                                        "A source attachment no longer exists in Gmail."));
                    } catch (GmailApiClient.GmailResponseTooLargeException exception) {
                        throw DraftException.badRequest(
                                "A source attachment response exceeds East's local forwarding limit.");
                    } catch (GmailApiClient.GmailApiException exception) {
                        if (exception.getHttpStatus() == 404) {
                            throw DraftException.conflict("A source attachment no longer exists in Gmail.");
                        }
                        throw DraftException.upstreamFailure();
                    }
                    try {
                        bytes = inline.bytes();
                        boolean metadataMatches = attachment.byteSize() == bytes.length
                                && attachment.filename().equals(
                                        DraftAttachmentPolicy.normalizeSourceFilename(inline.filename()))
                                && mimeType.equals(DraftAttachmentPolicy.normalizeSourceMimeType(inline.mimeType()))
                                && (inline.declaredSize() == null || inline.declaredSize() == bytes.length);
                        if (!metadataMatches) {
                            wipe(bytes);
                            throw DraftException.conflict("A source attachment changed in Gmail. Refresh and try again.");
                        }
                    } finally {
                        inline.destroy();
                    }
                } else {
                    try {
                        bytes = gmailApiClient.getAttachmentBytes(
                                accessToken, gmailMessageId, attachment.gmailAttachmentId());
                    } catch (GmailApiClient.GmailResponseTooLargeException exception) {
                        throw DraftException.badRequest(
                                "A source attachment response exceeds East's local forwarding limit.");
                    } catch (GmailApiClient.GmailApiException exception) {
                        if (exception.getHttpStatus() == 404) {
                            throw DraftException.conflict("A source attachment no longer exists in Gmail.");
                        }
                        throw DraftException.upstreamFailure();
                    }
                }
                if (bytes.length > DraftAttachmentPolicy.MAX_FILE_BYTES
                        || attachment.byteSize() != bytes.length
                        || actualTotal > DraftAttachmentPolicy.MAX_TOTAL_BYTES - bytes.length) {
                    wipe(bytes);
                    throw DraftException.badRequest("A source attachment exceeds East's staging limits.");
                }
                actualTotal += bytes.length;
                result.add(new DraftMimeBuilder.OutboundAttachment(filename, mimeType, bytes));
            }
            return result;
        } catch (RuntimeException exception) {
            result.forEach(attachment -> wipe(attachment.bytes()));
            throw exception;
        }
    }

    private List<SourceAttachment> extractSourceAttachments(JsonNode payload) {
        Map<String, SourceAttachment> attachments = new LinkedHashMap<>();
        collectSourceAttachments(payload, "0", attachments);
        return List.copyOf(attachments.values());
    }

    private void collectSourceAttachments(
            JsonNode part,
            String path,
            Map<String, SourceAttachment> attachments) {
        String filename = part.path("filename").asText("");
        JsonNode body = part.path("body");
        String attachmentId = body.path("attachmentId").asText("");
        JsonNode data = body.path("data");
        if (!filename.isBlank() && (!attachmentId.isBlank() || (data.isTextual() && !data.asText().isEmpty()))) {
            JsonNode sizeNode = body.path("size");
            Long size = sizeNode.isNumber() && sizeNode.asLong() >= 0 ? sizeNode.asLong() : null;
            if (size == null && attachmentId.isBlank() && data.isTextual()) {
                byte[] decoded = null;
                try {
                    decoded = Base64.getUrlDecoder().decode(data.asText());
                    size = (long) decoded.length;
                } catch (IllegalArgumentException exception) {
                    throw DraftException.conflict("A source attachment could not be decoded safely.");
                } finally {
                    wipe(decoded);
                }
            }
            SourceAttachment attachment = new SourceAttachment(
                    attachmentId.isBlank() ? null : attachmentId,
                    filename,
                    part.path("mimeType").asText("application/octet-stream"),
                    size,
                    attachmentId.isBlank() && data.isTextual() ? data.asText() : null);
            attachments.putIfAbsent(
                    attachmentId.isBlank() ? "inline:" + path : "attachment:" + attachmentId,
                    attachment);
        }
        JsonNode children = part.path("parts");
        if (children.isArray()) {
            int index = 0;
            for (JsonNode child : children) {
                collectSourceAttachments(child, path + "." + index++, attachments);
            }
        }
    }

    private static Map<String, String> headersFromGmail(JsonNode headersNode) {
        Map<String, String> result = new LinkedHashMap<>();
        if (headersNode.isArray()) {
            headersNode.forEach(header -> {
                String name = header.path("name").asText("").toLowerCase(Locale.ROOT);
                String value = header.path("value").asText("");
                if (!name.isBlank() && !value.isBlank()) {
                    result.putIfAbsent(name, value);
                }
            });
        }
        return Map.copyOf(result);
    }

    private static Map<String, String> stringMap(JsonNode object) {
        Map<String, String> result = new LinkedHashMap<>();
        object.fields().forEachRemaining(entry -> {
            if (entry.getValue().isTextual()) {
                String name = switch (entry.getKey().toLowerCase(Locale.ROOT).replace("-", "")) {
                    case "messageid" -> "message-id";
                    case "replyto" -> "reply-to";
                    case "inreplyto" -> "in-reply-to";
                    default -> entry.getKey().toLowerCase(Locale.ROOT);
                };
                result.put(name, entry.getValue().asText());
            }
        });
        return Map.copyOf(result);
    }

    private static List<String> extractAddresses(String header) {
        if (header == null || header.isBlank()) {
            return List.of();
        }
        Set<String> addresses = new LinkedHashSet<>();
        Matcher matcher = EMAIL_EXTRACTOR.matcher(header);
        while (matcher.find()) {
            addresses.add(matcher.group());
        }
        return List.copyOf(addresses);
    }

    private static String firstMessageId(String header) {
        if (header == null) {
            return null;
        }
        Matcher matcher = MESSAGE_ID.matcher(header);
        return matcher.find() ? matcher.group() : null;
    }

    private static String combineReferences(String existing, String sourceMessageId) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (existing != null) {
            Matcher matcher = MESSAGE_ID.matcher(existing);
            while (matcher.find()) {
                ids.add(matcher.group());
            }
        }
        if (sourceMessageId != null) {
            ids.add(sourceMessageId);
        }
        List<String> ordered = new ArrayList<>(ids);
        while (String.join(" ", ordered).length() > 7000 && ordered.size() > 1) {
            ordered.removeFirst();
        }
        return ordered.isEmpty() ? null : String.join(" ", ordered);
    }

    private static String textOrEmpty(JsonNode node) {
        return node.isTextual() ? node.asText() : "";
    }

    private static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    private record MessageContent(
            String bodyText,
            String bodyHtml,
            Map<String, String> headers,
            List<SourceAttachment> attachments) {
        MessageContent {
            attachments = List.copyOf(attachments);
        }
    }

    private record SourceAttachment(
            String gmailAttachmentId,
            String filename,
            String mimeType,
            Long byteSize,
            String inlineData) { }

    private record BodyParts(String plainText, String html) {
    }
}

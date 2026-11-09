package com.east.app.drafts;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

/** Reads a Gmail Draft resource into a bounded, sanitized, in-memory send-review snapshot. */
@Component
final class DraftGmailReviewParser {

    private static final int MAX_BODY_UTF8_BYTES = 1024 * 1024;
    private static final int MAX_SUBJECT_UTF8_BYTES = 1000;
    private static final String EMAIL_PATTERN =
            "[A-Za-z0-9_+\\-][A-Za-z0-9._%+\\-]*@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*";
    private static final Pattern BARE_EMAIL = Pattern.compile("^" + EMAIL_PATTERN + "$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAMED_EMAIL = Pattern.compile(
            "^(?:\"[^\"\\r\\n]{0,200}\"|[^<>\\r\\n,]{0,200})\\s*<(" + EMAIL_PATTERN + ")>$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ENCODED_WORD = Pattern.compile("=\\?([^?\\s]+)\\?([bBqQ])\\?([^?]*)\\?=");
    private static final Pattern MESSAGE_IDS = Pattern.compile("<[^<>\\s\\p{Cntrl}]{1,250}>");

    private final DraftHtmlSanitizer htmlSanitizer;

    DraftGmailReviewParser(DraftHtmlSanitizer htmlSanitizer) {
        this.htmlSanitizer = htmlSanitizer;
    }

    ParsedDraft parse(
            UUID localDraftId,
            DraftKind kind,
            String expectedFromAddress,
            String expectedGmailThreadId,
            JsonNode draftResource) {
        return parse(localDraftId, kind, expectedFromAddress, expectedGmailThreadId, draftResource, false);
    }

    ParsedDraft parse(
            UUID localDraftId,
            DraftKind kind,
            String expectedFromAddress,
            String expectedGmailThreadId,
            JsonNode draftResource,
            boolean retainInlineAttachmentBytes) {
        if (draftResource == null || !draftResource.path("message").isObject()) {
            throw DraftException.conflict("The Gmail draft could not be safely opened for review.");
        }
        JsonNode message = draftResource.path("message");
        JsonNode payload = message.path("payload");
        if (!payload.isObject()) {
            throw DraftException.conflict("The Gmail draft has no readable message content.");
        }
        String gmailThreadId = nullableText(message.path("threadId"));
        String gmailMessageId = nullableText(message.path("id"));
        if (gmailMessageId == null || !gmailMessageId.matches("[A-Za-z0-9_-]{1,256}")) {
            throw DraftException.conflict("The saved Gmail draft has no safe message reference for attachment review.");
        }
        if (gmailThreadId != null && !gmailThreadId.matches("[A-Za-z0-9_-]{1,256}")) {
            throw DraftException.conflict("The Gmail draft has an invalid conversation reference.");
        }
        if (kind == DraftKind.REPLY || kind == DraftKind.REPLY_ALL) {
            if (expectedGmailThreadId == null || !expectedGmailThreadId.equals(gmailThreadId)) {
                throw DraftException.conflict("The saved Gmail draft no longer belongs to its source conversation.");
            }
        }

        Map<String, List<String>> headers = readHeaders(payload.path("headers"));
        String fromAddress = parseSingleAddress(first(headers, "from"), "sender");
        if (!fromAddress.equalsIgnoreCase(expectedFromAddress)) {
            throw DraftException.conflict(
                    "The saved Gmail draft's sender differs from the selected mailbox. Review it in Gmail before sending.");
        }
        List<String> to = parseAddresses(join(headers, "to"), "To");
        List<String> cc = parseAddresses(join(headers, "cc"), "CC");
        List<String> bcc = parseAddresses(join(headers, "bcc"), "BCC");
        if (to.size() + cc.size() + bcc.size() > 100) {
            throw DraftException.conflict("The saved Gmail draft has too many recipients to review safely.");
        }
        String subject = decodeSubject(first(headers, "subject"));
        String inReplyTo = safeMessageIdHeader(first(headers, "in-reply-to"), true);
        String references = safeMessageIdHeader(first(headers, "references"), false);
        if ((kind == DraftKind.NEW || kind == DraftKind.FORWARD)
                && (inReplyTo != null || references != null)) {
            throw DraftException.conflict("The saved Gmail draft contains unsupported reply headers.");
        }
        if ((kind == DraftKind.REPLY || kind == DraftKind.REPLY_ALL)
                && (inReplyTo == null || references == null)) {
            throw DraftException.conflict("The saved Gmail reply no longer has usable threading headers.");
        }

        BodyAndAttachments content = parseParts(localDraftId, payload, retainInlineAttachmentBytes);
        try {
            String plainText = content.plainText();
            String richHtml = content.richHtml();
            if (plainText.isBlank() && !richHtml.isBlank()) {
                plainText = Jsoup.parse(richHtml).text();
            }
            if (richHtml.isBlank() && !plainText.isBlank()) {
                richHtml = plainTextAsHtml(plainText);
            }
            if (utf8Length(plainText) > MAX_BODY_UTF8_BYTES || utf8Length(richHtml) > MAX_BODY_UTF8_BYTES) {
                throw DraftException.conflict("The saved Gmail draft is too large to review safely in East.");
            }

            DraftPayload draftPayload = new DraftPayload(
                    kind,
                    null,
                    gmailThreadId,
                    inReplyTo,
                    references,
                    to,
                    cc,
                    bcc,
                    subject,
                    plainText,
                    richHtml);
            return new ParsedDraft(fromAddress, draftPayload, content.attachments(), gmailThreadId, gmailMessageId);
        } catch (RuntimeException exception) {
            if (retainInlineAttachmentBytes) {
                content.attachments().forEach(attachment -> wipe(attachment.inlineContentBytes()));
            }
            throw exception;
        }
    }

    private BodyAndAttachments parseParts(
            UUID localDraftId, JsonNode payload, boolean retainInlineAttachmentBytes) {
        List<String> plainParts = new ArrayList<>();
        List<String> htmlParts = new ArrayList<>();
        List<ParsedAttachment> attachments = new ArrayList<>();
        long[] totalAttachmentBytes = {0};
        try {
            collectParts(payload, "0", localDraftId, plainParts, htmlParts, attachments, totalAttachmentBytes,
                    retainInlineAttachmentBytes);
            String plainText = String.join("\n", plainParts);
            String rawHtml = String.join("<br>", htmlParts);
            if (!htmlSanitizer.isSafe(rawHtml)) {
                throw DraftException.conflict(
                        "The saved Gmail draft contains formatting East cannot safely display for send review.");
            }
            return new BodyAndAttachments(plainText, htmlSanitizer.sanitize(rawHtml), List.copyOf(attachments));
        } catch (RuntimeException exception) {
            attachments.forEach(attachment -> wipe(attachment.inlineContentBytes()));
            throw exception;
        }
    }

    private void collectParts(
            JsonNode part,
            String path,
            UUID localDraftId,
            List<String> plainParts,
            List<String> htmlParts,
            List<ParsedAttachment> attachments,
            long[] totalAttachmentBytes,
            boolean retainInlineAttachmentBytes) {
        String filename = part.path("filename").asText("");
        JsonNode body = part.path("body");
        String attachmentId = body.path("attachmentId").asText("");
        JsonNode children = part.path("parts");
        if (!filename.isBlank()) {
            addAttachment(localDraftId, path, filename, part.path("mimeType").asText(""), body,
                    attachmentId, attachments, totalAttachmentBytes, retainInlineAttachmentBytes);
            return;
        }
        if (children.isArray()) {
            int index = 0;
            for (JsonNode child : children) {
                collectParts(child, path + "." + index++, localDraftId,
                        plainParts, htmlParts, attachments, totalAttachmentBytes, retainInlineAttachmentBytes);
            }
            return;
        }

        String mimeType = part.path("mimeType").asText("").toLowerCase(Locale.ROOT);
        if (mimeType.equals("text/plain") || mimeType.equals("text/html")) {
            JsonNode dataNode = body.path("data");
            if (!dataNode.isTextual() || dataNode.asText().isEmpty()) {
                if (!attachmentId.isBlank()) {
                    throw DraftException.conflict("The saved Gmail draft body is not available for send review.");
                }
                return;
            }
            byte[] decoded = decodeUrlBase64(dataNode.asText());
            try {
                if (decoded.length > MAX_BODY_UTF8_BYTES) {
                    throw DraftException.conflict("The saved Gmail draft is too large to review safely in East.");
                }
                String value = decodeUtf8(decoded);
                if (mimeType.equals("text/plain")) plainParts.add(value);
                else htmlParts.add(value);
            } finally {
                wipe(decoded);
            }
            return;
        }
        if (!attachmentId.isBlank() || body.path("data").isTextual()) {
            throw DraftException.conflict("The saved Gmail draft contains a message part East cannot safely review.");
        }
        if (mimeType.startsWith("multipart/")) {
            throw DraftException.conflict("The saved Gmail draft has an incomplete MIME structure.");
        }
    }

    private static void addAttachment(
            UUID localDraftId,
            String path,
            String submittedFilename,
            String submittedMimeType,
            JsonNode body,
            String gmailAttachmentId,
            List<ParsedAttachment> attachments,
            long[] totalAttachmentBytes,
            boolean retainInlineAttachmentBytes) {
        JsonNode inlineData = body.path("data");
        if (gmailAttachmentId.isBlank() && (!inlineData.isTextual() || inlineData.asText().isEmpty())) {
            throw DraftException.conflict("An attachment in the saved Gmail draft has no readable content reference.");
        }
        if (attachments.size() >= DraftAttachmentPolicy.MAX_ATTACHMENTS_PER_DRAFT) {
            throw DraftException.conflict("The saved Gmail draft has too many attachments for East to review.");
        }
        JsonNode sizeNode = body.path("size");
        if (!sizeNode.isNumber() || sizeNode.asLong() < 0) {
            throw DraftException.conflict("An attachment in the saved Gmail draft has no verifiable size.");
        }
        long size = sizeNode.asLong();
        if (size > DraftAttachmentPolicy.MAX_FILE_BYTES
                || totalAttachmentBytes[0] > DraftAttachmentPolicy.MAX_TOTAL_BYTES - size) {
            throw DraftException.conflict("The saved Gmail draft attachments exceed East's review limits.");
        }
        String filename;
        try {
            filename = DraftAttachmentPolicy.normalizeSourceFilename(submittedFilename);
        } catch (DraftException exception) {
            throw DraftException.conflict("An attachment in the saved Gmail draft has an unsafe filename.");
        }
        String mimeType = DraftAttachmentPolicy.normalizeSourceMimeType(submittedMimeType);
        String identity = localDraftId + ":" + path + ":" + gmailAttachmentId + ":" + filename;
        UUID localAttachmentId = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
        String contentDigest = null;
        byte[] inlineContentBytes = null;
        if (gmailAttachmentId.isBlank()) {
            if (!inlineData.isTextual() || inlineData.asText().isEmpty()) {
                throw DraftException.conflict("An inline attachment in the saved Gmail draft has no readable content.");
            }
            byte[] decoded = decodeUrlBase64(inlineData.asText());
            try {
                if (decoded.length != size) {
                    throw DraftException.conflict("An attachment in the saved Gmail draft has an inconsistent size.");
                }
                contentDigest = sha256Base64Url(decoded);
                if (retainInlineAttachmentBytes) {
                    inlineContentBytes = decoded;
                    decoded = null;
                }
            } finally {
                wipe(decoded);
            }
        }
        attachments.add(new ParsedAttachment(
                new DraftAttachmentSummary(localAttachmentId, filename, mimeType, size),
                gmailAttachmentId.isBlank() ? null : gmailAttachmentId,
                contentDigest,
                inlineContentBytes));
        totalAttachmentBytes[0] += size;
    }

    private static String sha256Base64Url(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            try {
                return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            } finally {
                wipe(digest);
            }
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for attachment review.");
        }
    }

    private static Map<String, List<String>> readHeaders(JsonNode headersNode) {
        if (!headersNode.isArray()) {
            throw DraftException.conflict("The saved Gmail draft has no readable headers.");
        }
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headersNode.forEach(header -> {
            String name = header.path("name").asText("").toLowerCase(Locale.ROOT);
            if (!List.of("from", "to", "cc", "bcc", "subject", "in-reply-to", "references")
                    .contains(name)) {
                return;
            }
            String value = header.path("value").asText("");
            if (value.chars().anyMatch(Character::isISOControl)) {
                throw DraftException.conflict("The saved Gmail draft has unsafe header content.");
            }
            headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        });
        return headers;
    }

    private static String first(Map<String, List<String>> headers, String name) {
        List<String> values = headers.getOrDefault(name, List.of());
        if (values.size() > 1) {
            throw DraftException.conflict("The saved Gmail draft has ambiguous " + name + " headers.");
        }
        return values.isEmpty() ? null : values.getFirst();
    }

    private static String join(Map<String, List<String>> headers, String name) {
        return String.join(", ", headers.getOrDefault(name, List.of()));
    }

    private static List<String> parseAddresses(String value, String field) {
        if (value == null || value.isBlank()) return List.of();
        if (field.equals("To") && value.trim().equalsIgnoreCase("undisclosed-recipients:;")) {
            return List.of();
        }
        List<String> addresses = new ArrayList<>();
        for (String item : value.split(",", -1)) {
            addresses.add(parseSingleAddress(item, field));
        }
        return List.copyOf(addresses);
    }

    private static String parseSingleAddress(String value, String field) {
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
            throw DraftException.conflict("The saved Gmail draft has an unreadable " + field + " address.");
        }
        String item = value.trim();
        Matcher named = NAMED_EMAIL.matcher(item);
        String address;
        if (BARE_EMAIL.matcher(item).matches()) {
            address = item;
        } else if (named.matches()) {
            address = named.group(1);
        } else {
            throw DraftException.conflict("The saved Gmail draft has an unreadable " + field + " address.");
        }
        int at = address.indexOf('@');
        String localPart = address.substring(0, at);
        if (localPart.startsWith(".") || localPart.endsWith(".") || localPart.contains("..")) {
            throw DraftException.conflict("The saved Gmail draft has an invalid " + field + " address.");
        }
        return address;
    }

    private static String decodeSubject(String encoded) {
        if (encoded == null || encoded.isBlank()) return "";
        if (encoded.chars().anyMatch(Character::isISOControl)) {
            throw DraftException.conflict("The saved Gmail draft has an unsafe subject.");
        }
        Matcher matcher = ENCODED_WORD.matcher(encoded);
        StringBuilder output = new StringBuilder();
        int cursor = 0;
        boolean previousEncodedWord = false;
        while (matcher.find()) {
            String gap = encoded.substring(cursor, matcher.start());
            if (!previousEncodedWord || !gap.isBlank()) output.append(gap);
            output.append(decodeWord(matcher.group(1), matcher.group(2), matcher.group(3)));
            cursor = matcher.end();
            previousEncodedWord = true;
        }
        output.append(encoded.substring(cursor));
        String subject = output.toString();
        if (subject.contains("=?") || subject.length() > 500 || utf8Length(subject) > MAX_SUBJECT_UTF8_BYTES
                || subject.chars().anyMatch(Character::isISOControl)) {
            throw DraftException.conflict("The saved Gmail draft subject is too large or unsafe to review.");
        }
        return subject;
    }

    private static String decodeWord(String charsetName, String encoding, String value) {
        byte[] bytes = null;
        try {
            if (encoding.equalsIgnoreCase("B")) {
                bytes = Base64.getDecoder().decode(value);
            } else {
                ByteArrayOutputStream output = new ByteArrayOutputStream(value.length());
                for (int index = 0; index < value.length(); index++) {
                    char current = value.charAt(index);
                    if (current == '_') {
                        output.write(' ');
                    } else if (current == '=') {
                        if (index + 2 >= value.length()) throw new IllegalArgumentException();
                        int high = Character.digit(value.charAt(++index), 16);
                        int low = Character.digit(value.charAt(++index), 16);
                        if (high < 0 || low < 0) throw new IllegalArgumentException();
                        output.write((high << 4) | low);
                    } else if (current <= 0x7f) {
                        output.write(current);
                    } else {
                        throw new IllegalArgumentException();
                    }
                }
                bytes = output.toByteArray();
            }
            Charset charset = Charset.forName(charsetName);
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (IllegalArgumentException | CharacterCodingException exception) {
            throw DraftException.conflict("The saved Gmail draft subject cannot be decoded safely.");
        } finally {
            wipe(bytes);
        }
    }

    private static String safeMessageIdHeader(String value, boolean requireOne) {
        if (value == null || value.isBlank()) return null;
        Matcher matcher = MESSAGE_IDS.matcher(value);
        List<String> ids = new ArrayList<>();
        int cursor = 0;
        while (matcher.find()) {
            String gap = value.substring(cursor, matcher.start());
            if (!gap.isBlank()) {
                throw DraftException.conflict("The saved Gmail draft has an unsafe reply header.");
            }
            ids.add(matcher.group());
            cursor = matcher.end();
        }
        if (!value.substring(cursor).isBlank() || ids.isEmpty() || (requireOne && ids.size() != 1)) {
            throw DraftException.conflict("The saved Gmail draft has an unsafe reply header.");
        }
        return String.join(" ", ids);
    }

    private static String plainTextAsHtml(String plainText) {
        return "<p>" + new org.jsoup.nodes.TextNode(plainText).outerHtml()
                .replace("\r\n", "<br>")
                .replace("\n", "<br>") + "</p>";
    }

    private static byte[] decodeUrlBase64(String value) {
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw DraftException.conflict("The saved Gmail draft body could not be decoded safely.");
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw DraftException.conflict("The saved Gmail draft body is not valid UTF-8.");
        }
    }

    private static int utf8Length(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        try {
            return bytes.length;
        } finally {
            wipe(bytes);
        }
    }

    private static String nullableText(JsonNode node) {
        if (!node.isTextual() || node.asText().isBlank()) return null;
        return node.asText();
    }

    private static void wipe(byte[] bytes) {
        if (bytes != null) Arrays.fill(bytes, (byte) 0);
    }

    record ParsedDraft(
            String fromAddress,
            DraftPayload payload,
            List<ParsedAttachment> reviewedAttachments,
            String gmailThreadId,
            String gmailMessageId) {
        ParsedDraft {
            reviewedAttachments = List.copyOf(reviewedAttachments);
        }

        List<DraftAttachmentSummary> attachments() {
            return reviewedAttachments.stream().map(ParsedAttachment::summary).toList();
        }

        List<String> attachmentContentDigests() {
            return reviewedAttachments.stream().map(ParsedAttachment::contentDigest).toList();
        }

        ParsedDraft withAttachmentContentDigests(List<String> digests) {
            if (digests.size() != reviewedAttachments.size()) {
                throw DraftException.conflict("The saved Gmail draft attachments could not be verified.");
            }
            List<ParsedAttachment> updated = new ArrayList<>(reviewedAttachments.size());
            for (int index = 0; index < reviewedAttachments.size(); index++) {
                ParsedAttachment attachment = reviewedAttachments.get(index);
                updated.add(new ParsedAttachment(
                        attachment.summary(), attachment.gmailAttachmentId(), digests.get(index),
                        attachment.inlineContentBytes()));
            }
            return new ParsedDraft(fromAddress, payload, updated, gmailThreadId, gmailMessageId);
        }

        void wipeInlineAttachmentBytes() {
            reviewedAttachments.forEach(attachment -> wipe(attachment.inlineContentBytes()));
        }
    }

    record ParsedAttachment(
            DraftAttachmentSummary summary,
            String gmailAttachmentId,
            String contentDigest,
            byte[] inlineContentBytes) {
    }

    private record BodyAndAttachments(
            String plainText,
            String richHtml,
            List<ParsedAttachment> attachments) { }
}

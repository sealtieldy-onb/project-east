package com.east.app.drafts;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class DraftMimeBuilder {

    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_RAW_BYTES = 24 * 1024 * 1024;
    private static final Pattern SAFE_FROM = Pattern.compile(
            "^[A-Za-z0-9_+\\-][A-Za-z0-9._%+\\-]*@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$");

    private final SecureRandom secureRandom = new SecureRandom();

    byte[] build(String fromAddress, DraftPayload payload, List<OutboundAttachment> attachments) {
        if (fromAddress == null || !SAFE_FROM.matcher(fromAddress).matches()) {
            throw DraftException.conflict("The selected mailbox has an invalid sender address.");
        }
        if (payload.to().isEmpty() && payload.cc().isEmpty() && payload.bcc().isEmpty()) {
            throw DraftException.badRequest("Add at least one To, CC, or BCC recipient before sending.");
        }
        if (payload.plainTextBody().isEmpty() && payload.richTextHtml().isEmpty()) {
            throw DraftException.badRequest("Add a message before sending.");
        }

        String alternativeBoundary = boundary("east-alt");
        String mixedBoundary = boundary("east-mixed");
        boolean hasAttachments = !attachments.isEmpty();
        StringBuilder mime = new StringBuilder();
        mime.append("From: ").append(fromAddress).append("\r\n");
        appendAddressHeader(mime, "To", payload.to());
        appendAddressHeader(mime, "Cc", payload.cc());
        appendAddressHeader(mime, "Bcc", payload.bcc());
        appendSafeHeader(mime, "In-Reply-To", payload.inReplyTo());
        appendSafeHeader(mime, "References", payload.references());
        mime.append("Subject: ").append(encodeSubject(payload.subject())).append("\r\n");
        mime.append("MIME-Version: 1.0\r\n");
        if (hasAttachments) {
            mime.append("Content-Type: multipart/mixed; boundary=\"")
                    .append(mixedBoundary).append("\"\r\n\r\n");
            mime.append("--").append(mixedBoundary).append("\r\n");
        }
        mime.append("Content-Type: multipart/alternative; boundary=\"")
                .append(alternativeBoundary).append("\"\r\n\r\n");

        appendTextPart(mime, alternativeBoundary, "text/plain", payload.plainTextBody());
        appendTextPart(mime, alternativeBoundary, "text/html", payload.richTextHtml());
        mime.append("--").append(alternativeBoundary).append("--\r\n");

        for (OutboundAttachment attachment : attachments) {
            mime.append("--").append(mixedBoundary).append("\r\n")
                    .append("Content-Type: ").append(attachment.mimeType())
                    .append("; name*=UTF-8''").append(encodeHeaderParameter(attachment.filename())).append("\r\n")
                    .append("Content-Transfer-Encoding: base64\r\n")
                    .append("Content-Disposition: attachment; filename*=UTF-8''")
                    .append(encodeHeaderParameter(attachment.filename())).append("\r\n\r\n")
                    .append(encodeBody(attachment.bytes())).append("\r\n");
        }
        if (hasAttachments) {
            mime.append("--").append(mixedBoundary).append("--\r\n");
        }

        byte[] result = mime.toString().getBytes(StandardCharsets.UTF_8);
        if (result.length > MAX_RAW_BYTES) {
            java.util.Arrays.fill(result, (byte) 0);
            throw DraftException.badRequest("The composed message is too large to send.");
        }
        return result;
    }

    Map<String, Object> gmailMessage(String raw, DraftPayload payload) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("raw", raw);
        if (payload.gmailThreadId() != null) {
            message.put("threadId", payload.gmailThreadId());
        }
        return message;
    }

    private static void appendAddressHeader(StringBuilder mime, String name, List<String> addresses) {
        if (!addresses.isEmpty()) {
            mime.append(name).append(": ");
            int column = name.length() + 2;
            for (int index = 0; index < addresses.size(); index++) {
                String segment = (index == 0 ? "" : ", ") + addresses.get(index);
                if (column + segment.length() > 78 && column > name.length() + 2) {
                    mime.append(",\r\n ").append(addresses.get(index));
                    column = 1 + addresses.get(index).length();
                } else {
                    mime.append(segment);
                    column += segment.length();
                }
            }
            mime.append("\r\n");
        }
    }

    private static void appendSafeHeader(StringBuilder mime, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw DraftException.conflict("The source message has invalid reply headers.");
        }
        mime.append(name).append(": ");
        int column = name.length() + 2;
        for (String token : value.split(" ")) {
            if (token.isBlank()) continue;
            if (column + token.length() + 1 > 78 && column > name.length() + 2) {
                mime.append("\r\n ").append(token);
                column = token.length() + 1;
            } else {
                if (column > name.length() + 2) {
                    mime.append(' ');
                    column++;
                }
                mime.append(token);
                column += token.length();
            }
        }
        mime.append("\r\n");
    }

    private static void appendTextPart(StringBuilder mime, String boundary, String contentType, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        try {
            mime.append("--").append(boundary).append("\r\n")
                    .append("Content-Type: ").append(contentType).append("; charset=UTF-8\r\n")
                    .append("Content-Transfer-Encoding: base64\r\n\r\n")
                    .append(encodeBody(bytes)).append("\r\n");
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    private static String encodeSubject(String subject) {
        if (subject.isEmpty()) {
            return "";
        }
        List<byte[]> chunks = new ArrayList<>();
        ByteArrayOutputStream chunk = new ByteArrayOutputStream(42);
        subject.codePoints().forEach(codePoint -> {
            byte[] encoded = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
            if (chunk.size() + encoded.length > 42) {
                chunks.add(chunk.toByteArray());
                chunk.reset();
            }
            chunk.writeBytes(encoded);
            java.util.Arrays.fill(encoded, (byte) 0);
        });
        if (chunk.size() > 0) {
            chunks.add(chunk.toByteArray());
        }
        StringBuilder result = new StringBuilder();
        for (byte[] bytes : chunks) {
            if (!result.isEmpty()) {
                result.append("\r\n ");
            }
            result.append("=?UTF-8?B?")
                    .append(Base64.getEncoder().encodeToString(bytes))
                    .append("?=");
            java.util.Arrays.fill(bytes, (byte) 0);
        }
        return result.toString();
    }

    private String boundary(String prefix) {
        byte[] bytes = new byte[18];
        try {
            secureRandom.nextBytes(bytes);
            return prefix + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    private static String encodeHeaderParameter(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length * 3);
        for (byte item : bytes) {
            int unsigned = item & 0xff;
            if ((unsigned >= 'a' && unsigned <= 'z')
                    || (unsigned >= 'A' && unsigned <= 'Z')
                    || (unsigned >= '0' && unsigned <= '9')
                    || "!#$&+-.^_`|~".indexOf(unsigned) >= 0) {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%').append("0123456789ABCDEF".charAt(unsigned >>> 4))
                        .append("0123456789ABCDEF".charAt(unsigned & 0x0f));
            }
        }
        java.util.Arrays.fill(bytes, (byte) 0);
        return encoded.toString();
    }

    private static String encodeBody(byte[] bytes) {
        return Base64.getMimeEncoder(76, CRLF).encodeToString(bytes);
    }

    record OutboundAttachment(String filename, String mimeType, byte[] bytes) {
    }
}

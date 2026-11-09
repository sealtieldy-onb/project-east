package com.east.app.gmail;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Normalizes MIME Content-ID header values and cid: references for exact matching. */
public final class GmailContentId {

    private static final int MAX_CONTENT_ID_LENGTH = 998;

    private GmailContentId() { }

    public static String fromHeader(String value) {
        return normalize(value);
    }

    public static String fromCidUri(String value) {
        if (value == null || value.length() < 4 || !value.regionMatches(true, 0, "cid:", 0, 4)) return null;
        String rawId = value.substring(4).trim();
        if (rawId.isEmpty()) return null;
        try {
            // In a cid URI, plus is a literal character, unlike application/x-www-form-urlencoded.
            return normalize(URLDecoder.decode(rawId.replace("+", "%2B"), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.length() >= 2 && normalized.charAt(0) == '<'
                && normalized.charAt(normalized.length() - 1) == '>') {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.isEmpty() || normalized.length() > MAX_CONTENT_ID_LENGTH) return null;
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (Character.isWhitespace(character) || Character.isISOControl(character)
                    || character == '<' || character == '>') {
                return null;
            }
        }
        return normalized;
    }
}

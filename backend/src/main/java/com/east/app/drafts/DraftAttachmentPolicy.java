package com.east.app.drafts;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

import com.east.app.attachments.AttachmentFilenameSanitizer;

final class DraftAttachmentPolicy {

    static final int MAX_ATTACHMENTS_PER_DRAFT = 10;
    static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 16L * 1024 * 1024;

    private static final Pattern DECLARED_MIME_TYPE = Pattern.compile(
            "^[A-Za-z0-9!#$%&'*+.^_`|~-]{1,127}/[A-Za-z0-9!#$%&'*+.^_`|~-]{1,127}$");
    private static final Pattern SOURCE_MIME = Pattern.compile(
            "^[A-Za-z0-9!#$&^_.+-]{1,64}/[A-Za-z0-9!#$&^_.+-]{1,64}$");

    private DraftAttachmentPolicy() {
    }

    static String validateFilename(String submitted) {
        if (submitted == null || submitted.isBlank() || submitted.length() > 255
                || submitted.equals(".") || submitted.equals("..")
                || submitted.indexOf('/') >= 0 || submitted.indexOf('\\') >= 0) {
            throw DraftException.badRequest("Choose an attachment with a safe filename of at most 255 characters.");
        }
        for (int index = 0; index < submitted.length(); index++) {
            if (Character.isISOControl(submitted.charAt(index))) {
                throw DraftException.badRequest("Attachment filenames cannot contain control characters.");
            }
        }
        if (submitted.getBytes(StandardCharsets.UTF_8).length > 255) {
            throw DraftException.badRequest("The attachment filename is too long.");
        }
        return submitted;
    }

    static String normalizeUploadedMimeType(String submittedContentType) {
        String declared = submittedContentType == null
                ? ""
                : submittedContentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        // Uploaded files are sent only as attachments; keep MIME values header-safe without
        // inferring type from an extension or interpreting the file contents.
        return DECLARED_MIME_TYPE.matcher(declared).matches() ? declared : "application/octet-stream";
    }

    static String normalizeSourceMimeType(String mimeType) {
        if (mimeType == null) {
            return "application/octet-stream";
        }
        String normalized = mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return SOURCE_MIME.matcher(normalized).matches() ? normalized : "application/octet-stream";
    }

    static String normalizeSourceFilename(String submitted) {
        return validateFilename(AttachmentFilenameSanitizer.sanitize(submitted));
    }
}

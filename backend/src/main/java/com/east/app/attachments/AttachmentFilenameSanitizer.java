package com.east.app.attachments;

import java.util.regex.Pattern;

/** Removes filename controls that can alter paths, headers, or displayed character direction. */
public final class AttachmentFilenameSanitizer {

    private static final Pattern FORMAT_CONTROLS = Pattern.compile("\\p{Cf}+");
    private static final Pattern CONTROL_OR_PATH = Pattern.compile("[\\p{Cntrl}/\\\\]+");

    private AttachmentFilenameSanitizer() { }

    public static String sanitize(String filename) {
        String safe = filename == null ? "" : filename;
        safe = FORMAT_CONTROLS.matcher(safe).replaceAll("");
        safe = CONTROL_OR_PATH.matcher(safe).replaceAll("_").trim();
        if (safe.isEmpty() || safe.equals(".") || safe.equals("..")) {
            return "attachment";
        }
        if (safe.length() > 240) {
            safe = safe.substring(0, 240);
        }
        return safe;
    }
}

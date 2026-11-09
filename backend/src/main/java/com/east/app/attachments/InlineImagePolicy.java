package com.east.app.attachments;

import java.util.Locale;
import java.util.Set;

/** MIME types East can safely return to the browser as raster images. */
public final class InlineImagePolicy {

    private static final Set<String> SUPPORTED_RASTER_TYPES = Set.of(
            "image/gif", "image/jpeg", "image/png", "image/webp");

    private InlineImagePolicy() { }

    public static boolean supportsRaster(String mimeType) {
        if (mimeType == null) return false;
        String normalized = mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_RASTER_TYPES.contains(normalized);
    }
}

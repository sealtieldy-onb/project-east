package com.east.app.attachments;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InlineImagePolicyTest {

    @Test
    void allowsCommonRasterImagesAndRejectsActiveOrUnsupportedTypes() {
        assertTrue(InlineImagePolicy.supportsRaster("image/png"));
        assertTrue(InlineImagePolicy.supportsRaster("IMAGE/JPEG; charset=binary"));
        assertTrue(InlineImagePolicy.supportsRaster("image/gif"));
        assertTrue(InlineImagePolicy.supportsRaster("image/webp"));
        assertFalse(InlineImagePolicy.supportsRaster("image/svg+xml"));
        assertFalse(InlineImagePolicy.supportsRaster("application/pdf"));
        assertFalse(InlineImagePolicy.supportsRaster("image/x-icon"));
    }
}

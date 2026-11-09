package com.east.app.drafts;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class DraftMultipartConfiguration {

    private static final long MAX_REQUEST_BYTES = 12L * 1024 * 1024;
    // Keep accepted local files in memory during upload; do not stage plaintext files on disk.
    private static final int MEMORY_THRESHOLD_BYTES = (int) MAX_REQUEST_BYTES;

    @Bean
    MultipartConfigElement eastDraftMultipartConfig() {
        return new MultipartConfigElement(
                "",
                DraftAttachmentPolicy.MAX_FILE_BYTES,
                MAX_REQUEST_BYTES,
                MEMORY_THRESHOLD_BYTES);
    }
}

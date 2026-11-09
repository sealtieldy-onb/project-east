package com.east.app.drafts;

import java.util.UUID;

public record DraftAttachmentSummary(UUID id, String filename, String mimeType, long byteSize) {
}

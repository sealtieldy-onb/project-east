package com.east.app.drafts;

import java.time.Instant;
import java.util.UUID;

public record DraftListItem(
        UUID id,
        UUID mailboxId,
        String kind,
        String state,
        UUID sourceThreadId,
        int attachmentCount,
        Instant createdAt,
        Instant updatedAt,
        Instant savedAt,
        String reconciliationOperation,
        Instant reconciliationStartedAt) {
}

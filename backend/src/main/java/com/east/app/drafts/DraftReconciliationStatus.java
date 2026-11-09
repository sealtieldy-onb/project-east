package com.east.app.drafts;

import java.time.Instant;
import java.util.UUID;

public record DraftReconciliationStatus(
        UUID draftId,
        boolean required,
        boolean inProgress,
        String operation,
        String state,
        String previousState,
        Instant startedAt) {
}

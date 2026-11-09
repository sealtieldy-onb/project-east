package com.east.app.drafts;

import java.time.Instant;
import java.util.UUID;

public record DraftReconciliationResponse(UUID draftId, String state, String operation, Instant resolvedAt) {
}

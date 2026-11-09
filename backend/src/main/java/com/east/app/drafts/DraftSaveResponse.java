package com.east.app.drafts;

import java.time.Instant;
import java.util.UUID;

public record DraftSaveResponse(UUID id, String state, String gmailDraftId, Instant savedAt) {
}

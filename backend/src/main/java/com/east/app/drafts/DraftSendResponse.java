package com.east.app.drafts;

import java.time.Instant;
import java.util.UUID;

public record DraftSendResponse(UUID id, String state, String gmailMessageId, Instant sentAt) {
}

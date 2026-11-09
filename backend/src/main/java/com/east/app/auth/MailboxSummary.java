package com.east.app.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MailboxSummary(
        UUID id,
        String email,
        String displayName,
        int colorIndex,
        boolean reauthorizationRequired,
        Instant lastSyncAt,
        List<String> categories) {

    public MailboxSummary {
        categories = List.copyOf(categories);
    }
}

package com.east.app.auth;

import java.util.List;
import java.util.UUID;

public record MeResponse(UUID id, String email, String displayName, List<MailboxSummary> mailboxes) {
    public MeResponse {
        mailboxes = List.copyOf(mailboxes);
    }
}

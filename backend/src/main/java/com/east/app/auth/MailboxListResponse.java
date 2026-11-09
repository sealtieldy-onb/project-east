package com.east.app.auth;

import java.util.List;

public record MailboxListResponse(List<MailboxSummary> items) {
    public MailboxListResponse {
        items = List.copyOf(items);
    }
}

package com.east.app.auth;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Safe, durable state for the last scheduled or user-requested mailbox synchronization. */
public enum MailboxSyncStatus {
    PENDING("pending"),
    SYNCING("syncing"),
    READY("ready"),
    NEEDS_REAUTHORIZATION("needs_reauthorization"),
    PARTIAL_FAILURE("partial_failure"),
    ERROR("error");

    private final String value;

    MailboxSyncStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public static MailboxSyncStatus fromDatabase(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Mailbox sync status is missing.");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        for (MailboxSyncStatus status : values()) {
            if (status.value.equals(normalized)) return status;
        }
        throw new IllegalArgumentException("Mailbox sync status is invalid.");
    }
}

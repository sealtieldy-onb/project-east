package com.east.app.auth;

import java.time.Instant;
import java.io.Serializable;
import java.util.UUID;

record PendingAuthorization(
        String nonce,
        AuthIntent intent,
        UUID appUserId,
        Instant createdAt) implements Serializable {
    private static final long serialVersionUID = 1L;

    @Override
    public String toString() {
        return "PendingAuthorization[nonce=REDACTED, intent=" + intent
                + ", appUserId=REDACTED, createdAt=" + createdAt + "]";
    }
}

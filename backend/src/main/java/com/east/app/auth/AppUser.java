package com.east.app.auth;

import java.time.Instant;
import java.util.UUID;

public record AppUser(
        UUID id,
        String googleSubject,
        String email,
        String displayName,
        Instant createdAt,
        Instant updatedAt) {
}

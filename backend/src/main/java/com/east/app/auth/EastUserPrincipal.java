package com.east.app.auth;

import java.util.UUID;
import java.io.Serializable;

public record EastUserPrincipal(
        UUID appUserId,
        String googleSubject,
        String email,
        String displayName) implements Serializable {
    private static final long serialVersionUID = 1L;
}

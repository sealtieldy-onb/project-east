package com.east.app.auth;

import java.util.UUID;
import java.io.Serializable;
import java.security.Principal;

public record EastUserPrincipal(
        UUID appUserId,
        String googleSubject,
        String email,
        String displayName) implements Principal, Serializable {
    private static final long serialVersionUID = 1L;

    /** Keep Spring Session's principal index opaque and independent of email/display data. */
    @Override
    public String getName() {
        return appUserId.toString();
    }
}

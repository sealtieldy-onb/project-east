package com.east.app.auth;

import java.util.Set;

public record GoogleIdentity(
        String subject,
        String email,
        String displayName,
        Set<String> grantedScopes) {

    @Override
    public String toString() {
        return "GoogleIdentity[subject=REDACTED, email=REDACTED, displayName=REDACTED, grantedScopes="
                + grantedScopes.size() + "]";
    }
}

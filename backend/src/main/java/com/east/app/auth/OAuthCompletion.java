package com.east.app.auth;

record OAuthCompletion(
        PendingAuthorization pendingAuthorization,
        GoogleIdentity identity,
        String refreshToken) {

    @Override
    public String toString() {
        return "OAuthCompletion[pendingAuthorization=REDACTED, identity=REDACTED, refreshToken=REDACTED]";
    }
}

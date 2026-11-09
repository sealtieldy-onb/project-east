package com.east.app.gmail;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.east.app.auth.GoogleAccessTokenService;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GmailRequestGuardTest {

    @Test
    void currentRejectedTokenMarksExceptionAsReauthorizationRequired() {
        GmailApiClient.GmailApiException failure = guardedUnauthorized(true);

        assertTrue(failure.isMailboxReauthorizationRequired());
    }

    @Test
    void staleRejectedTokenDoesNotMarkExceptionAsReauthorizationRequired() {
        GmailApiClient.GmailApiException failure = guardedUnauthorized(false);

        assertFalse(failure.isMailboxReauthorizationRequired());
    }

    private static GmailApiClient.GmailApiException guardedUnauthorized(boolean currentToken) {
        GoogleAccessTokenService accessTokens = new GoogleAccessTokenService(null, null, null, Clock.systemUTC()) {
            @Override
            public synchronized boolean markReauthorizationRequiredIfCurrentAccessToken(
                    UUID appUserId, UUID mailboxId, String rejectedAccessToken) {
                return currentToken && "synthetic-token".equals(rejectedAccessToken);
            }
        };
        GmailApiClient.GmailApiException expected = new GmailApiClient.GmailApiException(401, "invalidCredentials");

        GmailApiClient.GmailApiException thrown = assertThrows(
                GmailApiClient.GmailApiException.class,
                () -> GmailRequestGuard.call(
                        accessTokens,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "synthetic-token",
                        () -> { throw expected; }));
        return thrown;
    }
}

package com.east.app.gmail;

import com.east.app.auth.GoogleAccessTokenService;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Persists mailbox reauthorization state when a Gmail request rejects its bearer token. */
public final class GmailRequestGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger(GmailRequestGuard.class);

    private GmailRequestGuard() { }

    public static <T> T call(
            GoogleAccessTokenService accessTokenService,
            UUID appUserId,
            UUID mailboxId,
            String accessToken,
            Supplier<T> request) {
        try {
            return request.get();
        } catch (GmailApiClient.GmailApiException exception) {
            markUnauthorized(accessTokenService, appUserId, mailboxId, accessToken, exception);
            throw exception;
        }
    }

    public static void run(
            GoogleAccessTokenService accessTokenService,
            UUID appUserId,
            UUID mailboxId,
            String accessToken,
            Runnable request) {
        try {
            request.run();
        } catch (GmailApiClient.GmailApiException exception) {
            markUnauthorized(accessTokenService, appUserId, mailboxId, accessToken, exception);
            throw exception;
        }
    }

    private static void markUnauthorized(
            GoogleAccessTokenService accessTokenService,
            UUID appUserId,
            UUID mailboxId,
            String accessToken,
            GmailApiClient.GmailApiException exception) {
        if (exception.getHttpStatus() != 401) return;
        try {
            boolean required = accessTokenService.markReauthorizationRequiredIfCurrentAccessToken(
                    appUserId, mailboxId, accessToken);
            exception.setMailboxReauthorizationRequired(required);
        } catch (RuntimeException statusException) {
            // Preserve Gmail's original response. Never log provider response text or tokens.
            LOGGER.warn("Gmail authorization failure state could not be persisted for mailbox {}", mailboxId);
        }
    }
}

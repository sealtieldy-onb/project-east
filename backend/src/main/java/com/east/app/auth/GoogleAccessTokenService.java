package com.east.app.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.east.app.vault.EncryptedPayload;
import com.east.app.vault.VaultService;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Internal-only token access for Gmail services; tokens are never returned by controllers. */
@Service
public class GoogleAccessTokenService {

    private static final long CACHE_SKEW_SECONDS = 60;
    private static final long MAX_TOKEN_LIFETIME_SECONDS = 86_400;

    private final MailboxRepository mailboxRepository;
    private final VaultService vaultService;
    private final GoogleTokenClient tokenClient;
    private final Clock clock;
    private final Map<UUID, CachedAccessToken> accessTokenCache = new HashMap<>();

    public GoogleAccessTokenService(
            MailboxRepository mailboxRepository,
            VaultService vaultService,
            GoogleTokenClient tokenClient,
            Clock clock) {
        this.mailboxRepository = mailboxRepository;
        this.vaultService = vaultService;
        this.tokenClient = tokenClient;
        this.clock = clock;
    }

    public synchronized String getValidAccessToken(UUID appUserId, UUID mailboxId) {
        // The local key remains loaded while East is running; background token refresh does not
        // interact with browser activity or extend any user session.
        vaultService.requireUnlocked();
        ConnectedMailbox mailbox = mailboxRepository.findOwned(appUserId, mailboxId)
                .orElseThrow(AuthNotFoundException::new);
        if (!mailbox.grantedScopes().contains(GoogleOAuthService.GMAIL_MODIFY_SCOPE)) {
            throw new AuthFlowException(HttpStatus.FORBIDDEN, "This mailbox does not have the required Gmail permission.");
        }
        if (mailbox.reauthorizationRequired()) {
            destroyCachedToken(mailboxId);
            throw new AuthFlowException(HttpStatus.UNAUTHORIZED, "This mailbox needs to be reconnected to Google.");
        }

        Instant now = clock.instant();
        CachedAccessToken cached = accessTokenCache.get(mailboxId);
        if (cached != null && cached.expiresAt().isAfter(now.plusSeconds(CACHE_SKEW_SECONDS))) {
            return cached.value();
        }
        destroyCachedToken(mailboxId);

        byte[] nonce = mailbox.refreshTokenNonce();
        byte[] ciphertext = mailbox.refreshTokenCiphertext();
        if (nonce == null || ciphertext == null) {
            markReauthorizationRequired(appUserId, mailboxId);
            throw new AuthFlowException(HttpStatus.UNAUTHORIZED, "This mailbox needs to be connected again.");
        }

        byte[] refreshTokenBytes = null;
        EncryptedPayload encryptedPayload = new EncryptedPayload(nonce, ciphertext);
        try {
            // The local encryption key remains loaded while East is running; token refresh
            // does not depend on browser activity or extend an app unlock window.
            refreshTokenBytes = vaultService.decryptLocal(
                    encryptedPayload,
                    MailboxTokenAad.refreshToken(appUserId, mailboxId, mailbox.googleSubject()));
            String refreshToken = new String(refreshTokenBytes, StandardCharsets.UTF_8);
            GoogleTokenClient.GoogleTokenResponse refreshed;
            try {
                refreshed = tokenClient.refreshAccessToken(refreshToken);
            } catch (GoogleTokenClient.GoogleTokenExchangeException exception) {
                if (exception.invalidGrant()) {
                    markReauthorizationRequired(appUserId, mailboxId);
                    throw new AuthFlowException(
                            HttpStatus.UNAUTHORIZED,
                            "This mailbox needs to be reconnected to Google.");
                }
                throw new AuthFlowException(
                        HttpStatus.BAD_GATEWAY,
                        "Google could not refresh this mailbox right now. Check your connection and try again.");
            }
            if (!StringUtils.hasText(refreshed.accessToken())
                    || refreshed.expiresInSeconds() == null
                    || refreshed.expiresInSeconds() <= 0
                    || refreshed.expiresInSeconds() > MAX_TOKEN_LIFETIME_SECONDS) {
                throw new AuthFlowException(HttpStatus.BAD_GATEWAY, "Google did not return a valid mailbox access token.");
            }
            Instant expiresAt = now.plusSeconds(refreshed.expiresInSeconds());
            accessTokenCache.put(mailboxId, new CachedAccessToken(refreshed.accessToken(), expiresAt));
            return refreshed.accessToken();
        } finally {
            encryptedPayload.destroy();
            Arrays.fill(nonce, (byte) 0);
            Arrays.fill(ciphertext, (byte) 0);
            if (refreshTokenBytes != null) {
                Arrays.fill(refreshTokenBytes, (byte) 0);
            }
        }
    }

    synchronized void invalidate(UUID mailboxId) {
        destroyCachedToken(mailboxId);
    }

    /** Invalidates any cached bearer token and durably marks the owned mailbox for reconnection. */
    public synchronized void markReauthorizationRequired(UUID appUserId, UUID mailboxId) {
        destroyCachedToken(mailboxId);
        mailboxRepository.markReauthorizationRequired(appUserId, mailboxId);
    }

    /**
     * Marks a mailbox only when the rejected bearer token is still the current cached token.
     * A Gmail 401 can arrive after reconnect or refresh has replaced the token used by that
     * in-flight request; such a stale response must not invalidate the newer connection.
     */
    public synchronized boolean markReauthorizationRequiredIfCurrentAccessToken(
            UUID appUserId, UUID mailboxId, String rejectedAccessToken) {
        CachedAccessToken current = accessTokenCache.get(mailboxId);
        if (current == null || !current.matches(rejectedAccessToken)) {
            return false;
        }
        destroyCachedToken(mailboxId);
        mailboxRepository.markReauthorizationRequired(appUserId, mailboxId);
        return true;
    }

    /** Clears tokens promptly after the vault expires without extending its idle timer. */
    @Scheduled(fixedDelay = 1_000L)
    public synchronized void clearCacheWhenVaultLocks() {
        try {
            if (!vaultService.status().unlocked()) {
                clearAccessTokenCache();
            }
        } catch (RuntimeException exception) {
            // Fail closed if local vault state cannot be checked.
            clearAccessTokenCache();
        }
    }

    private void destroyCachedToken(UUID mailboxId) {
        CachedAccessToken removed = accessTokenCache.remove(mailboxId);
        if (removed != null) {
            removed.destroy();
        }
    }

    private void clearAccessTokenCache() {
        accessTokenCache.values().forEach(CachedAccessToken::destroy);
        accessTokenCache.clear();
    }

    private static final class CachedAccessToken {
        private final char[] value;
        private final Instant expiresAt;

        private CachedAccessToken(String value, Instant expiresAt) {
            this.value = value.toCharArray();
            this.expiresAt = expiresAt;
        }

        private String value() {
            return new String(value);
        }

        private Instant expiresAt() {
            return expiresAt;
        }

        private boolean matches(String candidate) {
            if (candidate == null || candidate.length() != value.length) return false;
            int difference = 0;
            for (int index = 0; index < value.length; index++) {
                difference |= value[index] ^ candidate.charAt(index);
            }
            return difference == 0;
        }

        private void destroy() {
            Arrays.fill(value, '\0');
        }
    }
}

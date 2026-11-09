package com.east.app.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

@Component
public class OAuthSessionStateStore {

    private static final String SESSION_ATTRIBUTE = OAuthSessionStateStore.class.getName() + ".pending";
    private static final Duration MAX_AGE = Duration.ofMinutes(10);
    private static final int MAX_PENDING_PER_SESSION = 8;
    private static final int RANDOM_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Clock clock;

    public OAuthSessionStateStore(Clock clock) {
        this.clock = clock;
    }

    public IssuedAuthorization issue(HttpSession session, AuthIntent intent, UUID appUserId) {
        if (intent == AuthIntent.LINK_MAILBOX && appUserId == null) {
            throw new IllegalArgumentException("Mailbox linking requires an app user.");
        }
        if (intent == AuthIntent.SIGN_IN && appUserId != null) {
            throw new IllegalArgumentException("Sign-in authorization cannot target an app user.");
        }

        String state = randomToken();
        String nonce = randomToken();
        PendingAuthorization pending = new PendingAuthorization(nonce, intent, appUserId, clock.instant());
        synchronized (session) {
            Map<String, PendingAuthorization> entries = getOrCreateEntries(session);
            removeExpired(entries);
            while (entries.size() >= MAX_PENDING_PER_SESSION) {
                String oldestState = entries.keySet().iterator().next();
                entries.remove(oldestState);
            }
            entries.put(state, pending);
        }
        return new IssuedAuthorization(state, nonce);
    }

    public PendingAuthorization consume(HttpSession session, String state) {
        if (session == null || state == null || state.length() > 128) {
            throw new InvalidOAuthStateException();
        }
        synchronized (session) {
            Map<String, PendingAuthorization> entries = getEntries(session);
            if (entries == null) {
                throw new InvalidOAuthStateException();
            }
            PendingAuthorization pending = entries.remove(state);
            if (entries.isEmpty()) {
                session.removeAttribute(SESSION_ATTRIBUTE);
            }
            if (pending == null || isExpired(pending)) {
                throw new InvalidOAuthStateException();
            }
            return pending;
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[RANDOM_BYTES];
        try {
            secureRandom.nextBytes(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, PendingAuthorization> getEntries(HttpSession session) {
        Object attribute = session.getAttribute(SESSION_ATTRIBUTE);
        if (!(attribute instanceof Map<?, ?>)) {
            return null;
        }
        return (Map<String, PendingAuthorization>) attribute;
    }

    private Map<String, PendingAuthorization> getOrCreateEntries(HttpSession session) {
        Map<String, PendingAuthorization> entries = getEntries(session);
        if (entries == null) {
            entries = new LinkedHashMap<>();
            session.setAttribute(SESSION_ATTRIBUTE, entries);
        }
        return entries;
    }

    private void removeExpired(Map<String, PendingAuthorization> entries) {
        Instant now = clock.instant();
        entries.entrySet().removeIf(entry -> isExpired(entry.getValue(), now));
    }

    private boolean isExpired(PendingAuthorization pending) {
        return isExpired(pending, clock.instant());
    }

    private boolean isExpired(PendingAuthorization pending, Instant now) {
        return !now.isBefore(pending.createdAt().plus(MAX_AGE));
    }

    public static final class InvalidOAuthStateException extends RuntimeException {
        public InvalidOAuthStateException() {
            super("The Google authorization request is invalid or expired. Start again.");
        }
    }
}

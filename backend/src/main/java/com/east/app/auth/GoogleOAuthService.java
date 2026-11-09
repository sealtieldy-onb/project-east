package com.east.app.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class GoogleOAuthService {

    public static final String GMAIL_MODIFY_SCOPE = "https://www.googleapis.com/auth/gmail.modify";
    private static final String AUTHORIZATION_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final Set<String> IDENTITY_SCOPES = Set.of("openid", "email", "profile");
    private static final String USERINFO_EMAIL_SCOPE = "https://www.googleapis.com/auth/userinfo.email";
    private static final String USERINFO_PROFILE_SCOPE = "https://www.googleapis.com/auth/userinfo.profile";

    private final GoogleOAuthProperties properties;
    private final OAuthSessionStateStore stateStore;
    private final GoogleTokenClient tokenClient;
    private final JwtDecoder jwtDecoder;
    private final Clock clock;

    public GoogleOAuthService(
            GoogleOAuthProperties properties,
            OAuthSessionStateStore stateStore,
            GoogleTokenClient tokenClient,
            JwtDecoder googleIdTokenDecoder,
            Clock clock) {
        this.properties = properties;
        this.stateStore = stateStore;
        this.tokenClient = tokenClient;
        this.jwtDecoder = googleIdTokenDecoder;
        this.clock = clock;
    }

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    public String startSignIn(HttpServletRequest request) {
        requireConfigured();
        IssuedAuthorization issued = stateStore.issue(
                request.getSession(true), AuthIntent.SIGN_IN, null);
        return authorizationUri(issued, AuthIntent.SIGN_IN);
    }

    public String startMailboxLink(HttpServletRequest request, java.util.UUID appUserId) {
        requireConfigured();
        IssuedAuthorization issued = stateStore.issue(
                request.getSession(true), AuthIntent.LINK_MAILBOX, appUserId);
        return authorizationUri(issued, AuthIntent.LINK_MAILBOX);
    }

    public OAuthCompletion completeCallback(String code, String state, HttpServletRequest request) {
        requireConfigured();
        PendingAuthorization pending = consumeState(state, request);
        if (!StringUtils.hasText(code) || code.length() > 4096) {
            throw new AuthFlowException(HttpStatus.BAD_REQUEST, "The Google authorization response is incomplete. Start again.");
        }

        GoogleTokenClient.GoogleTokenResponse tokens;
        try {
            tokens = tokenClient.exchangeAuthorizationCode(code);
        } catch (GoogleTokenClient.GoogleTokenExchangeException exception) {
            throw new AuthFlowException(HttpStatus.BAD_GATEWAY, "Google token exchange failed. Start the authorization flow again.");
        }
        if (!StringUtils.hasText(tokens.idToken())) {
            throw new AuthFlowException(HttpStatus.BAD_GATEWAY, "Google did not return a valid identity token. Start again.");
        }

        Set<String> requestedScopes = requestedScopes(pending.intent());
        Set<String> grantedScopes = grantedScopes(tokens.scope(), requestedScopes);
        if (!grantedScopes.containsAll(requestedScopes)) {
            throw new AuthFlowException(HttpStatus.FORBIDDEN, "Google did not grant the permissions East requested.");
        }

        Jwt jwt = decodeAndValidate(tokens.idToken());
        validateNonce(jwt, pending.nonce());
        String subject = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        Object emailVerified = jwt.getClaims().get("email_verified");
        if (!StringUtils.hasText(subject)
                || !StringUtils.hasText(email)
                || !Boolean.TRUE.equals(emailVerified)) {
            throw new AuthFlowException(HttpStatus.BAD_GATEWAY, "Google did not provide a verified account identity.");
        }

        String displayName = jwt.getClaimAsString("name");
        GoogleIdentity identity = new GoogleIdentity(subject, email, displayName, grantedScopes);
        String refreshToken = StringUtils.hasText(tokens.refreshToken()) ? tokens.refreshToken() : null;
        return new OAuthCompletion(pending, identity, refreshToken);
    }

    public void consumeDeniedCallback(String state, HttpServletRequest request) {
        requireConfigured();
        consumeState(state, request);
        throw new AuthFlowException(HttpStatus.BAD_REQUEST, "Google authorization was denied or canceled.");
    }

    private PendingAuthorization consumeState(String state, HttpServletRequest request) {
        try {
            return stateStore.consume(request.getSession(false), state);
        } catch (OAuthSessionStateStore.InvalidOAuthStateException exception) {
            throw new AuthFlowException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    private Jwt decodeAndValidate(String idToken) {
        try {
            Jwt jwt = jwtDecoder.decode(idToken);
            if (jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(clock.instant())) {
                throw new AuthFlowException(HttpStatus.BAD_GATEWAY, "Google returned an expired identity token.");
            }
            if (!"RS256".equals(jwt.getHeaders().get("alg"))) {
                throw new AuthFlowException(HttpStatus.BAD_GATEWAY, "Google returned an unsupported identity token.");
            }
            return jwt;
        } catch (JwtException exception) {
            throw new AuthFlowException(HttpStatus.BAD_GATEWAY, "Google identity token validation failed. Start again.");
        }
    }

    private void validateNonce(Jwt jwt, String expectedNonce) {
        String actualNonce = jwt.getClaimAsString("nonce");
        if (!StringUtils.hasText(actualNonce)
                || !constantTimeEquals(expectedNonce, actualNonce)) {
            throw new AuthFlowException(HttpStatus.BAD_GATEWAY, "Google identity token nonce validation failed. Start again.");
        }
    }

    private String authorizationUri(IssuedAuthorization issued, AuthIntent intent) {
        Set<String> scopes = requestedScopes(intent);
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(AUTHORIZATION_ENDPOINT)
                .queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", properties.redirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", String.join(" ", scopes))
                .queryParam("state", issued.state())
                .queryParam("nonce", issued.nonce());
        if (intent == AuthIntent.LINK_MAILBOX) {
            uri.queryParam("access_type", "offline")
                    .queryParam("prompt", "select_account consent");
        }
        return uri.build().encode(StandardCharsets.UTF_8).toUriString();
    }

    private static Set<String> requestedScopes(AuthIntent intent) {
        LinkedHashSet<String> scopes = new LinkedHashSet<>(IDENTITY_SCOPES);
        if (intent == AuthIntent.LINK_MAILBOX) {
            scopes.add(GMAIL_MODIFY_SCOPE);
        }
        return Set.copyOf(scopes);
    }

    private static Set<String> grantedScopes(String responseScope, Set<String> requestedScopes) {
        if (!StringUtils.hasText(responseScope)) {
            // OAuth 2.0 omits scope when the grant matches the requested set.
            return requestedScopes;
        }
        return Arrays.stream(responseScope.trim().split("\\s+"))
                .map(GoogleOAuthService::canonicalScope)
                .filter(StringUtils::hasText)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String canonicalScope(String scope) {
        return switch (scope) {
            case USERINFO_EMAIL_SCOPE -> "email";
            case USERINFO_PROFILE_SCOPE -> "profile";
            default -> scope;
        };
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        try {
            return MessageDigest.isEqual(expectedBytes, actualBytes);
        } finally {
            Arrays.fill(expectedBytes, (byte) 0);
            Arrays.fill(actualBytes, (byte) 0);
        }
    }

    private void requireConfigured() {
        if (!properties.isConfigured()) {
            throw new AuthFlowException(HttpStatus.SERVICE_UNAVAILABLE, "Google OAuth is not configured for this local installation.");
        }
    }
}

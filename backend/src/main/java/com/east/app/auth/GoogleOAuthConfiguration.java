package com.east.app.auth;

import java.util.Set;
import java.time.Instant;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration
@EnableConfigurationProperties(GoogleOAuthProperties.class)
public class GoogleOAuthConfiguration {

    static final String GOOGLE_ISSUER = "https://accounts.google.com";
    static final String GOOGLE_ALT_ISSUER = "accounts.google.com";
    static final String GOOGLE_JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";

    @Bean
    JwtDecoder googleIdTokenDecoder(GoogleOAuthProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(GOOGLE_JWKS_URI)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                issuerValidator(),
                expirationValidator(),
                audienceValidator(properties)));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> issuerValidator() {
        return jwt -> {
            String issuer = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
            if (issuer != null && Set.of(GOOGLE_ISSUER, GOOGLE_ALT_ISSUER).contains(issuer)) {
                return OAuth2TokenValidatorResult.success();
            }
            return invalidToken("Google ID token issuer is invalid.");
        };
    }

    private static OAuth2TokenValidator<Jwt> expirationValidator() {
        return jwt -> {
            Instant expiresAt = jwt.getExpiresAt();
            if (expiresAt != null && expiresAt.isAfter(Instant.now())) {
                return OAuth2TokenValidatorResult.success();
            }
            return invalidToken("Google ID token is expired or has no expiration.");
        };
    }

    private static OAuth2TokenValidator<Jwt> audienceValidator(GoogleOAuthProperties properties) {
        return jwt -> {
            if (!properties.isConfigured() || !jwt.getAudience().contains(properties.clientId())) {
                return invalidToken("Google ID token audience is invalid.");
            }
            Object authorizedParty = jwt.getClaims().get("azp");
            if (jwt.getAudience().size() > 1
                    && !properties.clientId().equals(authorizedParty)) {
                return invalidToken("Google ID token authorized party is invalid.");
            }
            if (authorizedParty != null && !properties.clientId().equals(authorizedParty)) {
                return invalidToken("Google ID token authorized party is invalid.");
            }
            return OAuth2TokenValidatorResult.success();
        };
    }

    private static OAuth2TokenValidatorResult invalidToken(String description) {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", description, null));
    }
}

package com.east.app.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "east.google")
public record GoogleOAuthProperties(String clientId, String clientSecret, String redirectUri, int serverPort) {

    private static final String CALLBACK_PATH = "/login/oauth2/code/google";

    public boolean isConfigured() {
        return StringUtils.hasText(clientId)
                && StringUtils.hasText(clientSecret)
                && ("http://localhost:" + serverPort + CALLBACK_PATH).equals(redirectUri);
    }

    @Override
    public String toString() {
        return "GoogleOAuthProperties[clientIdConfigured=" + StringUtils.hasText(clientId)
                + ", clientSecretConfigured=" + StringUtils.hasText(clientSecret)
                + ", redirectUriMatchesLocalServer="
                + ("http://localhost:" + serverPort + CALLBACK_PATH).equals(redirectUri) + "]";
    }
}

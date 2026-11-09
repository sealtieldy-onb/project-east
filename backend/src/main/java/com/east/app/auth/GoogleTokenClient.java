package com.east.app.auth;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class GoogleTokenClient {

    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final int MAX_ERROR_BODY_BYTES = 8_192;
    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    private final RestClient restClient;
    private final GoogleOAuthProperties properties;

    public GoogleTokenClient(RestClient.Builder restClientBuilder, GoogleOAuthProperties properties) {
        this.restClient = Objects.requireNonNull(restClientBuilder, "restClientBuilder").build();
        this.properties = properties;
    }

    GoogleTokenResponse exchangeAuthorizationCode(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("code", code);
        form.add("grant_type", "authorization_code");
        form.add("redirect_uri", properties.redirectUri());
        return postTokenRequest(form);
    }

    GoogleTokenResponse refreshAccessToken(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("refresh_token", refreshToken);
        form.add("grant_type", "refresh_token");
        return postTokenRequest(form);
    }

    private GoogleTokenResponse postTokenRequest(MultiValueMap<String, String> form) {
        try {
            GoogleTokenResponse response = restClient.post()
                    .uri(TOKEN_ENDPOINT)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(GoogleTokenResponse.class);
            if (response == null) {
                throw new GoogleTokenExchangeException();
            }
            return response;
        } catch (RestClientResponseException exception) {
            throw new GoogleTokenExchangeException(isInvalidGrant(exception));
        } catch (RestClientException exception) {
            // Do not propagate provider response bodies or request details to callers or logs.
            throw new GoogleTokenExchangeException();
        }
    }

    /** Inspect only the OAuth error code; never retain or expose Google's response text. */
    private static boolean isInvalidGrant(RestClientResponseException exception) {
        if (exception.getStatusCode().value() != 400) {
            return false;
        }
        byte[] responseBody = exception.getResponseBodyAsByteArray();
        try {
            if (responseBody.length == 0 || responseBody.length > MAX_ERROR_BODY_BYTES) {
                return false;
            }
            try (JsonParser parser = JSON_FACTORY.createParser(responseBody)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    return false;
                }
                boolean foundError = false;
                boolean invalidGrant = false;
                JsonToken token;
                while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
                    if (token == null || token != JsonToken.FIELD_NAME) {
                        return false;
                    }
                    String fieldName = parser.currentName();
                    JsonToken valueToken = parser.nextToken();
                    if (valueToken == null) {
                        return false;
                    }
                    if ("error".equals(fieldName)) {
                        if (foundError || valueToken != JsonToken.VALUE_STRING) {
                            return false;
                        }
                        foundError = true;
                        invalidGrant = "invalid_grant".equals(parser.getText());
                    } else {
                        parser.skipChildren();
                    }
                }
                return foundError && invalidGrant && parser.nextToken() == null;
            } catch (IOException ignored) {
                return false;
            }
        } finally {
            Arrays.fill(responseBody, (byte) 0);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GoogleTokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") Long expiresInSeconds,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("scope") String scope,
            @JsonProperty("id_token") String idToken,
            @JsonProperty("token_type") String tokenType) {
        @Override
        public String toString() {
            return "GoogleTokenResponse[accessToken=REDACTED, refreshToken=REDACTED, idToken=REDACTED]";
        }
    }

    static final class GoogleTokenExchangeException extends RuntimeException {
        private final boolean invalidGrant;

        GoogleTokenExchangeException() {
            this(false);
        }

        GoogleTokenExchangeException(boolean invalidGrant) {
            super("The Google token exchange failed. Start the authorization flow again.");
            this.invalidGrant = invalidGrant;
        }

        boolean invalidGrant() {
            return invalidGrant;
        }
    }
}

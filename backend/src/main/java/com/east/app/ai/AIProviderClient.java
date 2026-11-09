package com.east.app.ai;

import com.east.app.vault.EncryptedPayload;
import com.east.app.vault.VaultService;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Explicit-call-only OpenAI Chat Completions adapter. It has no controller, startup hook,
 * scheduled job, retry, or persistence path of its own.
 */
@Component
public final class AIProviderClient implements AIProviderGateway {

    private static final String OPENAI_API_BASE = "https://api.openai.com/v1";
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final byte[] API_KEY_AAD = "east-ai-provider-key:v1".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_REQUEST_BYTES = 2 * 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int READ_BUFFER_BYTES = 8 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(90);
    private static final JsonFactory STRICT_RESPONSE_FACTORY = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(64)
                    .maxStringLength(MAX_RESPONSE_BYTES)
                    .maxNumberLength(128)
                    .build())
            .build();

    private final JdbcTemplate jdbcTemplate;
    private final VaultService vaultService;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final StructuredOutputValidator structuredOutputValidator;

    @Autowired
    public AIProviderClient(
            JdbcTemplate jdbcTemplate,
            VaultService vaultService,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            StructuredOutputValidator structuredOutputValidator) {
        this(jdbcTemplate, vaultService, restClientBuilder, objectMapper, structuredOutputValidator,
                createProductionRequestFactory());
    }

    AIProviderClient(
            JdbcTemplate jdbcTemplate,
            VaultService vaultService,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            StructuredOutputValidator structuredOutputValidator,
            ClientHttpRequestFactory requestFactory) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.vaultService = Objects.requireNonNull(vaultService, "vaultService");
        this.restClient = Objects.requireNonNull(restClientBuilder, "restClientBuilder")
                .clone()
                .requestFactory(Objects.requireNonNull(requestFactory, "requestFactory"))
                .build();
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.structuredOutputValidator = Objects.requireNonNull(
                structuredOutputValidator, "structuredOutputValidator");
    }

    private static ClientHttpRequestFactory createProductionRequestFactory() {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return requestFactory;
    }

    /**
     * Sends one caller-prepared text request to the currently configured provider and validates
     * its JSON response locally. Callers must enforce explicit user intent and provider disclosure.
     */
    public <T> T complete(AIProviderPrompt prompt, StructuredOutputContract<T> contract) {
        return completeWithMetadata(prompt, contract).value();
    }

    /** Performs one explicit model call and returns the non-secret configuration used for it. */
    public <T> Completion<T> completeWithMetadata(
            AIProviderPrompt prompt,
            StructuredOutputContract<T> contract) {
        return completeWithMetadata(prompt, contract, null);
    }

    /** Checks a previously disclosed configuration version before any content leaves East. */
    public <T> Completion<T> completeWithMetadata(
            AIProviderPrompt prompt,
            StructuredOutputContract<T> contract,
            String expectedConfigurationVersion) {
        Objects.requireNonNull(prompt, "prompt");
        JsonNode schema = structuredOutputValidator.schemaFor(contract);
        StoredProviderConfiguration stored = loadStoredConfiguration();
        byte[] apiKeyBytes = null;
        byte[] requestBody = null;
        byte[] responseBody = null;
        try {
            if (expectedConfigurationVersion != null
                    && !expectedConfigurationVersion.equals(stored.metadata().configurationVersion())) {
                throw new AIProviderException(AIProviderException.Reason.DISCLOSURE_CHANGED);
            }
            apiKeyBytes = decryptApiKey(stored);
            if (apiKeyBytes.length < 8 || apiKeyBytes.length > 4096
                    || stored.modelId().length() > 256) {
                throw new AIProviderException(AIProviderException.Reason.INVALID_CONFIGURATION);
            }
            String apiKey = new String(apiKeyBytes, StandardCharsets.UTF_8);
            requestBody = buildRequestBody(stored.modelId(), prompt, contract.name(), schema);
            responseBody = send(stored.endpoint(), apiKey, requestBody);
            String output = extractStructuredContent(responseBody);
            try {
                T value = structuredOutputValidator.validate(output, contract);
                return new Completion<>(value, stored.metadata());
            } catch (StructuredOutputValidationException exception) {
                throw new AIProviderException(AIProviderException.Reason.INVALID_MODEL_OUTPUT);
            }
        } finally {
            stored.destroy();
            wipe(apiKeyBytes);
            wipe(requestBody);
            wipe(responseBody);
        }
    }

    /** Returns provider/model disclosure data without loading or returning the API key. */
    public ProviderMetadata providerMetadata() {
        List<ProviderMetadata> rows = jdbcTemplate.query(
                """
                SELECT provider_type, base_url, model_id
                FROM ai_provider_configuration
                WHERE singleton_id = 1 AND api_key_ciphertext IS NOT NULL
                """,
                (resultSet, rowNumber) -> metadata(
                        resultSet.getString("provider_type"),
                        resultSet.getString("base_url"),
                        resultSet.getString("model_id")));
        return rows.stream().findFirst().orElse(null);
    }

    private StoredProviderConfiguration loadStoredConfiguration() {
        List<StoredProviderConfiguration> rows;
        try {
            rows = jdbcTemplate.query(
                    """
                    SELECT provider_type, base_url, model_id, api_key_nonce, api_key_ciphertext
                    FROM ai_provider_configuration
                    WHERE singleton_id = 1
                    """,
                    (resultSet, rowNumber) -> new StoredProviderConfiguration(
                            resultSet.getString("provider_type"),
                            resultSet.getString("base_url"),
                            resultSet.getString("model_id"),
                            resultSet.getBytes("api_key_nonce"),
                            resultSet.getBytes("api_key_ciphertext")));
        } catch (RuntimeException exception) {
            throw new AIProviderException(AIProviderException.Reason.INVALID_CONFIGURATION);
        }
        if (rows.isEmpty()) {
            throw new AIProviderException(AIProviderException.Reason.NOT_CONFIGURED);
        }
        StoredProviderConfiguration stored = rows.getFirst();
        if (stored.apiKeyNonce == null && stored.apiKeyCiphertext == null) {
            stored.destroy();
            throw new AIProviderException(AIProviderException.Reason.NOT_CONFIGURED);
        }
        if (stored.apiKeyNonce == null || stored.apiKeyCiphertext == null
                || stored.modelId == null || stored.modelId.isBlank()) {
            stored.destroy();
            throw new AIProviderException(AIProviderException.Reason.INVALID_CONFIGURATION);
        }

        String baseUrl = switch (stored.providerType == null ? "" : stored.providerType) {
            case "OPENAI" -> OPENAI_API_BASE;
            case "OPENAI_COMPATIBLE" -> stored.baseUrl;
            default -> null;
        };
        URI endpoint = endpointFor(baseUrl);
        if (endpoint == null) {
            stored.destroy();
            throw new AIProviderException(AIProviderException.Reason.INVALID_CONFIGURATION);
        }
        stored.endpoint = endpoint;
        return stored;
    }

    private byte[] decryptApiKey(StoredProviderConfiguration stored) {
        EncryptedPayload encrypted = null;
        try {
            vaultService.requireUnlocked();
            encrypted = new EncryptedPayload(stored.apiKeyNonce, stored.apiKeyCiphertext);
            return vaultService.decryptLocal(encrypted, API_KEY_AAD);
        } catch (RuntimeException exception) {
            throw new AIProviderException(AIProviderException.Reason.VAULT_UNAVAILABLE);
        } finally {
            if (encrypted != null) encrypted.destroy();
        }
    }

    private URI endpointFor(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return null;
        try {
            URI base = URI.create(baseUrl.trim());
            String scheme = base.getScheme();
            String host = base.getHost();
            boolean loopbackHttp = "http".equalsIgnoreCase(scheme) && isLoopback(host);
            boolean secureHttps = "https".equalsIgnoreCase(scheme) && host != null;
            if ((!loopbackHttp && !secureHttps)
                    || base.getRawUserInfo() != null
                    || base.getRawQuery() != null
                    || base.getRawFragment() != null) {
                return null;
            }
            String normalized = base.toString().replaceAll("/+$", "");
            return URI.create(normalized + CHAT_COMPLETIONS_PATH);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static boolean isLoopback(String host) {
        return host != null && (host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1")
                || host.equals("[::1]")
                || host.equals("::1"));
    }

    private byte[] buildRequestBody(
            String modelId,
            AIProviderPrompt prompt,
            String schemaName,
            JsonNode schema) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", modelId);
        ArrayNode messages = root.putArray("messages");
        for (AIProviderPrompt.Message message : prompt.messages()) {
            ObjectNode item = messages.addObject();
            item.put("role", message.role().wireValue());
            item.put("content", message.content());
        }
        ObjectNode responseFormat = root.putObject("response_format");
        responseFormat.put("type", "json_schema");
        ObjectNode schemaEnvelope = responseFormat.putObject("json_schema");
        schemaEnvelope.put("name", schemaName);
        schemaEnvelope.put("strict", true);
        schemaEnvelope.set("schema", schema);

        try {
            byte[] request = objectMapper.writeValueAsBytes(root);
            if (request.length > MAX_REQUEST_BYTES) {
                wipe(request);
                throw new IllegalArgumentException("The model request exceeds the local byte limit.");
            }
            return request;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("The model request could not be encoded.");
        }
    }

    private byte[] send(URI endpoint, String apiKey, byte[] requestBody) {
        try {
            return restClient.post()
                    .uri(endpoint)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new AIProviderException(AIProviderException.Reason.PROVIDER_REJECTED);
                        }
                        try {
                            return readBounded(response.getBody());
                        } catch (IOException exception) {
                            throw new AIProviderException(AIProviderException.Reason.PROVIDER_UNAVAILABLE);
                        }
                    });
        } catch (AIProviderException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new AIProviderException(AIProviderException.Reason.PROVIDER_UNAVAILABLE);
        }
    }

    private byte[] readBounded(InputStream input) throws IOException {
        byte[] buffer = new byte[MAX_RESPONSE_BYTES + 1];
        int length = 0;
        try (input) {
            while (length < buffer.length) {
                int read = input.read(buffer, length, Math.min(READ_BUFFER_BYTES, buffer.length - length));
                if (read < 0) break;
                if (read == 0) continue;
                length += read;
            }
            if (length > MAX_RESPONSE_BYTES) {
                throw new AIProviderException(AIProviderException.Reason.INVALID_PROVIDER_RESPONSE);
            }
            return Arrays.copyOf(buffer, length);
        } finally {
            wipe(buffer);
        }
    }

    private String extractStructuredContent(byte[] responseBody) {
        try (JsonParser parser = STRICT_RESPONSE_FACTORY.createParser(responseBody)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new AIProviderException(AIProviderException.Reason.INVALID_PROVIDER_RESPONSE);
            }
            JsonNode root = objectMapper.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) {
                throw new AIProviderException(AIProviderException.Reason.INVALID_PROVIDER_RESPONSE);
            }
            JsonNode choices = root == null ? null : root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                throw new AIProviderException(AIProviderException.Reason.INVALID_PROVIDER_RESPONSE);
            }
            JsonNode choice = choices.get(0);
            JsonNode finishReason = choice.get("finish_reason");
            if (finishReason != null && !finishReason.isNull()
                    && (!finishReason.isTextual() || !"stop".equals(finishReason.textValue()))) {
                throw new AIProviderException(AIProviderException.Reason.INVALID_PROVIDER_RESPONSE);
            }
            JsonNode message = choice.get("message");
            if (message == null || !message.isObject()) {
                throw new AIProviderException(AIProviderException.Reason.INVALID_PROVIDER_RESPONSE);
            }
            JsonNode refusal = message.get("refusal");
            if (refusal != null && !refusal.isNull()
                    && (!refusal.isTextual() || !refusal.textValue().isBlank())) {
                throw new AIProviderException(AIProviderException.Reason.INVALID_MODEL_OUTPUT);
            }
            JsonNode content = message.get("content");
            if (content == null || !content.isTextual() || content.textValue().isBlank()) {
                throw new AIProviderException(AIProviderException.Reason.INVALID_PROVIDER_RESPONSE);
            }
            return content.textValue();
        } catch (IOException exception) {
            throw new AIProviderException(AIProviderException.Reason.INVALID_PROVIDER_RESPONSE);
        }
    }

    private static void wipe(byte[] value) {
        if (value != null) Arrays.fill(value, (byte) 0);
    }

    private static final class StoredProviderConfiguration {
        private final String providerType;
        private final String baseUrl;
        private final String modelId;
        private final byte[] apiKeyNonce;
        private final byte[] apiKeyCiphertext;
        private URI endpoint;

        private StoredProviderConfiguration(
                String providerType,
                String baseUrl,
                String modelId,
                byte[] apiKeyNonce,
                byte[] apiKeyCiphertext) {
            this.providerType = providerType;
            this.baseUrl = baseUrl;
            this.modelId = modelId;
            this.apiKeyNonce = apiKeyNonce;
            this.apiKeyCiphertext = apiKeyCiphertext;
        }

        private String modelId() {
            return modelId;
        }

        private URI endpoint() {
            return endpoint;
        }

        private ProviderMetadata metadata() {
            return AIProviderClient.metadata(providerType, baseUrl, modelId);
        }

        private void destroy() {
            wipe(apiKeyNonce);
            wipe(apiKeyCiphertext);
        }

        @Override
        public String toString() {
            return "StoredProviderConfiguration[providerType=" + providerType
                    + ", baseUrl=" + baseUrl + ", modelId=" + modelId + ", apiKey=REDACTED]";
        }
    }

    private static ProviderMetadata metadata(String providerType, String baseUrl, String modelId) {
        return new ProviderMetadata(providerType, baseUrl, modelId,
                configurationVersion(providerType, baseUrl, modelId));
    }

    /** A non-secret fingerprint for binding consent to the exact disclosed provider destination and model. */
    public static String configurationVersion(String providerType, String baseUrl, String modelId) {
        String canonical = providerType + "\n" + Objects.toString(baseUrl, "") + "\n" + modelId;
        byte[] canonicalBytes = canonical.getBytes(StandardCharsets.UTF_8);
        byte[] digest = null;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(canonicalBytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.");
        } finally {
            wipe(canonicalBytes);
            wipe(digest);
        }
    }

    public record ProviderMetadata(String providerType, String baseUrl, String modelId, String configurationVersion) { }

    public record Completion<T>(T value, ProviderMetadata provider) { }
}

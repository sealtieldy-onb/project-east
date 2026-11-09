package com.east.app.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.east.app.vault.EncryptedPayload;
import com.east.app.vault.LocalVaultKeyStore;
import com.east.app.vault.VaultCryptoService;
import com.east.app.vault.VaultRecord;
import com.east.app.vault.VaultRepository;
import com.east.app.vault.VaultService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.AbstractClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.client.RestClient;

class AIProviderClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] API_KEY_AAD = "east-ai-provider-key:v1".getBytes(StandardCharsets.US_ASCII);
    private static final String SYNTHETIC_API_KEY = "synthetic-only-provider-key";
    private static final String SYNTHETIC_ENDPOINT = "http://127.0.0.1:43127/v1";
    private static final String SYNTHETIC_INPUT = "Synthetic report input only.";

    @TempDir
    Path tempDir;

    @Test
    void explicitCallUsesDecryptedKeyAndSendsBoundedStructuredRequest() throws Exception {
        FakeHttpTransport transport = new FakeHttpTransport(HttpStatus.OK, providerResponse("{\"summary\":\"Synthetic summary\"}"));
        Fixture fixture = Fixture.configured(tempDir.resolve("configured"), transport, SYNTHETIC_ENDPOINT);
        try {
            assertTrue(transport.requests.isEmpty(), "Constructing the adapter must not call a provider.");

            String summary = fixture.client.complete(prompt(SYNTHETIC_INPUT), new SummaryContract());

            assertEquals("Synthetic summary", summary);
            assertEquals(1, transport.requests.size(), "An explicit completion makes exactly one request.");
            CapturedRequest request = transport.requests.getFirst();
            assertEquals(HttpMethod.POST, request.method());
            assertEquals(SYNTHETIC_ENDPOINT + "/chat/completions", request.uri().toString());
            assertEquals("Bearer " + SYNTHETIC_API_KEY, request.authorization());
            assertEquals(MediaType.APPLICATION_JSON_VALUE, request.contentType());

            JsonNode body = MAPPER.readTree(request.body());
            assertEquals("synthetic-model", body.path("model").asText());
            assertEquals("system", body.path("messages").get(0).path("role").asText());
            assertEquals("user", body.path("messages").get(1).path("role").asText());
            assertEquals(SYNTHETIC_INPUT, body.path("messages").get(1).path("content").asText());
            assertEquals("json_schema", body.path("response_format").path("type").asText());
            assertTrue(body.path("response_format").path("json_schema").path("strict").asBoolean());
            assertEquals("east_summary", body.path("response_format").path("json_schema").path("name").asText());
            assertFalse(body.toString().contains("Authorization"));
        } finally {
            fixture.destroy();
            transport.destroy();
        }
    }

    @Test
    void refusesNonLoopbackHttpEndpointBeforeMakingARequest() throws Exception {
        FakeHttpTransport transport = new FakeHttpTransport(HttpStatus.OK, providerResponse("{\"summary\":\"unused\"}"));
        Fixture fixture = Fixture.configured(
                tempDir.resolve("unsafe-endpoint"),
                transport,
                "http://example.invalid/v1");
        try {
            AIProviderException exception = assertThrows(
                    AIProviderException.class,
                    () -> fixture.client.complete(prompt(SYNTHETIC_INPUT), new SummaryContract()));

            assertEquals(AIProviderException.Reason.INVALID_CONFIGURATION, exception.reason());
            assertTrue(transport.requests.isEmpty());
        } finally {
            fixture.destroy();
            transport.destroy();
        }
    }

    @Test
    void providerFailureIsSanitizedAndIsNotRetried() throws Exception {
        String syntheticProviderBody = "synthetic provider diagnostic must not escape";
        FakeHttpTransport transport = new FakeHttpTransport(HttpStatus.BAD_REQUEST, syntheticProviderBody.getBytes(StandardCharsets.UTF_8));
        Fixture fixture = Fixture.configured(tempDir.resolve("provider-error"), transport, SYNTHETIC_ENDPOINT);
        try {
            AIProviderException exception = assertThrows(
                    AIProviderException.class,
                    () -> fixture.client.complete(prompt(SYNTHETIC_INPUT), new SummaryContract()));

            assertEquals(AIProviderException.Reason.PROVIDER_REJECTED, exception.reason());
            assertFalse(exception.getMessage().contains(syntheticProviderBody));
            assertEquals(1, transport.requests.size(), "A failed provider request must not be retried automatically.");
        } finally {
            fixture.destroy();
            transport.destroy();
        }
    }

    @Test
    void rejectsInvalidStructuredModelOutput() throws Exception {
        FakeHttpTransport transport = new FakeHttpTransport(HttpStatus.OK, providerResponse("{\"summary\":42}"));
        Fixture fixture = Fixture.configured(tempDir.resolve("invalid-output"), transport, SYNTHETIC_ENDPOINT);
        try {
            AIProviderException exception = assertThrows(
                    AIProviderException.class,
                    () -> fixture.client.complete(prompt(SYNTHETIC_INPUT), new SummaryContract()));

            assertEquals(AIProviderException.Reason.INVALID_MODEL_OUTPUT, exception.reason());
            assertEquals(1, transport.requests.size());
        } finally {
            fixture.destroy();
            transport.destroy();
        }
    }

    @Test
    void missingProviderConfigurationDoesNotCallTransport() throws Exception {
        FakeHttpTransport transport = new FakeHttpTransport(HttpStatus.OK, providerResponse("{\"summary\":\"unused\"}"));
        Fixture fixture = Fixture.unconfigured(tempDir.resolve("unconfigured"), transport);
        try {
            AIProviderException exception = assertThrows(
                    AIProviderException.class,
                    () -> fixture.client.complete(prompt(SYNTHETIC_INPUT), new SummaryContract()));

            assertEquals(AIProviderException.Reason.NOT_CONFIGURED, exception.reason());
            assertTrue(transport.requests.isEmpty());
        } finally {
            fixture.destroy();
            transport.destroy();
        }
    }

    private static AIProviderPrompt prompt(String userContent) {
        return new AIProviderPrompt(List.of(
                new AIProviderPrompt.Message(AIProviderPrompt.Role.SYSTEM, "Return the required synthetic test schema."),
                new AIProviderPrompt.Message(AIProviderPrompt.Role.USER, userContent)));
    }

    private static byte[] providerResponse(String structuredContent) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode choices = root.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("finish_reason", "stop");
        choice.putObject("message").put("content", structuredContent);
        return MAPPER.writeValueAsBytes(root);
    }

    private static final class SummaryContract implements StructuredOutputContract<String> {

        @Override
        public String name() {
            return "east_summary";
        }

        @Override
        public JsonNode schema() {
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("type", "object");
            ObjectNode properties = schema.putObject("properties");
            properties.putObject("summary").put("type", "string");
            schema.putArray("required").add("summary");
            schema.put("additionalProperties", false);
            return schema;
        }

        @Override
        public String validate(JsonNode output) {
            if (output.size() != 1 || !output.path("summary").isTextual()) {
                throw new IllegalArgumentException("Synthetic summary contract rejected the response.");
            }
            String summary = output.path("summary").asText();
            if (summary.isBlank() || summary.length() > 160) {
                throw new IllegalArgumentException("Synthetic summary contract rejected the response.");
            }
            return summary;
        }
    }

    private static final class Fixture {
        private final MemoryVaultRepository vaultRepository = new MemoryVaultRepository();
        private final VaultService vault;
        private final MemoryJdbcTemplate jdbc = new MemoryJdbcTemplate();
        private final AIProviderClient client;
        private byte[] encryptedNonce;
        private byte[] encryptedCiphertext;

        private Fixture(Path keyPath, FakeHttpTransport transport, boolean configured, String baseUrl) {
            vault = new VaultService(
                    vaultRepository,
                    new VaultCryptoService(),
                    null,
                    new LocalVaultKeyStore(keyPath.resolve("vault.key").toString()));
            vault.initializeForLocalUse();
            if (configured) configureProvider(baseUrl);
            client = new AIProviderClient(
                    jdbc,
                    vault,
                    RestClient.builder(),
                    MAPPER,
                    new StructuredOutputValidator(),
                    transport);
        }

        private static Fixture configured(Path keyPath, FakeHttpTransport transport, String baseUrl) {
            return new Fixture(keyPath, transport, true, baseUrl);
        }

        private static Fixture unconfigured(Path keyPath, FakeHttpTransport transport) {
            return new Fixture(keyPath, transport, false, null);
        }

        private void configureProvider(String baseUrl) {
            byte[] plaintext = SYNTHETIC_API_KEY.getBytes(StandardCharsets.UTF_8);
            EncryptedPayload encrypted = null;
            try {
                encrypted = vault.encryptLocal(plaintext, API_KEY_AAD);
                encryptedNonce = encrypted.nonce();
                encryptedCiphertext = encrypted.ciphertext();
                jdbc.providerRow("OPENAI_COMPATIBLE", baseUrl, "synthetic-model", encryptedNonce, encryptedCiphertext);
            } finally {
                Arrays.fill(plaintext, (byte) 0);
                if (encrypted != null) encrypted.destroy();
            }
        }

        private void destroy() {
            wipe(encryptedNonce);
            wipe(encryptedCiphertext);
            jdbc.destroy();
        }
    }

    private static final class MemoryJdbcTemplate extends JdbcTemplate {
        private Map<String, Object> providerRow;

        private void providerRow(
                String providerType,
                String baseUrl,
                String model,
                byte[] nonce,
                byte[] ciphertext) {
            providerRow = new LinkedHashMap<>();
            providerRow.put("provider_type", providerType);
            providerRow.put("base_url", baseUrl);
            providerRow.put("model_id", model);
            providerRow.put("api_key_nonce", nonce.clone());
            providerRow.put("api_key_ciphertext", ciphertext.clone());
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper) {
            if (!sql.contains("FROM ai_provider_configuration")) {
                throw new AssertionError("Provider adapter queried an unexpected table.");
            }
            if (providerRow == null) return List.of();
            try {
                return List.of(rowMapper.mapRow(resultSet(providerRow), 0));
            } catch (SQLException exception) {
                throw new IllegalStateException("Could not map synthetic provider settings.", exception);
            }
        }

        private static ResultSet resultSet(Map<String, Object> row) {
            return (ResultSet) Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(),
                    new Class<?>[] {ResultSet.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "getString" -> (String) row.get(arguments[0]);
                        case "getBytes" -> copy((byte[]) row.get(arguments[0]));
                        case "toString" -> "SyntheticProviderResultSet";
                        default -> throw new UnsupportedOperationException(
                                "Unexpected synthetic ResultSet call: " + method.getName());
                    });
        }

        private void destroy() {
            if (providerRow == null) return;
            wipe((byte[]) providerRow.get("api_key_nonce"));
            wipe((byte[]) providerRow.get("api_key_ciphertext"));
            providerRow = null;
        }
    }

    private static final class MemoryVaultRepository extends VaultRepository {
        private VaultRecord state;

        private MemoryVaultRepository() {
            super(null);
        }

        @Override
        public synchronized Optional<VaultRecord> find() {
            if (state == null) return Optional.empty();
            return Optional.of(new VaultRecord(state.salt(), state.encryptedVerificationMarker()));
        }

        @Override
        public synchronized boolean isInitialized() {
            return state != null;
        }

        @Override
        public synchronized void create(byte[] salt, byte[] encryptedVerificationMarker) {
            if (state != null) throw new DuplicateKeyException("Synthetic vault is already initialized.");
            state = new VaultRecord(salt, encryptedVerificationMarker);
        }
    }

    private record CapturedRequest(URI uri, HttpMethod method, String authorization, String contentType, byte[] body) {
    }

    private static final class FakeHttpTransport implements ClientHttpRequestFactory {
        private final HttpStatus responseStatus;
        private final byte[] responseBody;
        private final List<CapturedRequest> requests = new ArrayList<>();

        private FakeHttpTransport(HttpStatus responseStatus, byte[] responseBody) {
            this.responseStatus = responseStatus;
            this.responseBody = responseBody.clone();
        }

        @Override
        public org.springframework.http.client.ClientHttpRequest createRequest(java.net.URI uri, HttpMethod method) {
            return new AbstractClientHttpRequest() {
                private final ByteArrayOutputStream requestBody = new ByteArrayOutputStream();

                @Override public HttpMethod getMethod() { return method; }
                @Override public java.net.URI getURI() { return uri; }
                @Override protected OutputStream getBodyInternal(HttpHeaders headers) { return requestBody; }

                @Override
                protected ClientHttpResponse executeInternal(HttpHeaders headers) {
                    requests.add(new CapturedRequest(
                            uri,
                            method,
                            headers.getFirst(HttpHeaders.AUTHORIZATION),
                            headers.getFirst(HttpHeaders.CONTENT_TYPE),
                            requestBody.toByteArray()));
                    return new SyntheticResponse(responseStatus, responseBody);
                }
            };
        }

        private void destroy() {
            for (CapturedRequest request : requests) wipe(request.body());
            requests.clear();
            wipe(responseBody);
        }
    }

    private static final class SyntheticResponse implements ClientHttpResponse {
        private final HttpStatus status;
        private final HttpHeaders headers;
        private final byte[] body;

        private SyntheticResponse(HttpStatus status, byte[] body) {
            this.status = status;
            this.body = body.clone();
            HttpHeaders configured = new HttpHeaders();
            configured.setContentType(MediaType.APPLICATION_JSON);
            headers = HttpHeaders.readOnlyHttpHeaders(configured);
        }

        @Override public HttpStatus getStatusCode() { return status; }
        @Override public String getStatusText() { return status.getReasonPhrase(); }
        @Override public HttpHeaders getHeaders() { return headers; }
        @Override public ByteArrayInputStream getBody() { return new ByteArrayInputStream(body); }
        @Override public void close() { wipe(body); }
    }

    private static byte[] copy(byte[] value) {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }

    private static void wipe(byte[] value) {
        if (value != null) Arrays.fill(value, (byte) 0);
    }
}

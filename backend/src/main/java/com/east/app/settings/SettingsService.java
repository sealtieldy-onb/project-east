package com.east.app.settings;

import com.east.app.ai.AIProviderClient;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.east.app.vault.EncryptedPayload;
import com.east.app.vault.VaultService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettingsService {

    private static final byte[] API_KEY_AAD = "east-ai-provider-key:v1".getBytes(StandardCharsets.US_ASCII);

    private final JdbcTemplate jdbcTemplate;
    private final VaultService vaultService;

    public SettingsService(JdbcTemplate jdbcTemplate, VaultService vaultService) {
        this.jdbcTemplate = jdbcTemplate;
        this.vaultService = vaultService;
    }

    @Transactional(readOnly = true)
    public AIProviderSettingsResponse getAIProvider() {
        List<AIProviderConfiguration> rows = jdbcTemplate.query(
                """
                SELECT provider_type, base_url, model_id, (api_key_ciphertext IS NOT NULL) AS key_configured
                FROM ai_provider_configuration
                WHERE singleton_id = 1
                """,
                (resultSet, rowNumber) -> new AIProviderConfiguration(
                        resultSet.getString("provider_type"),
                        resultSet.getString("base_url"),
                        resultSet.getString("model_id"),
                        resultSet.getBoolean("key_configured")));
        if (rows.isEmpty()) {
            return AIProviderSettingsResponse.empty();
        }

        AIProviderConfiguration configuration = rows.getFirst();
        return new AIProviderSettingsResponse(
                toApiProvider(configuration.providerType()),
                configuration.baseUrl(),
                configuration.modelId(),
                configuration.keyConfigured(),
                configuration.keyConfigured()
                        ? AIProviderClient.configurationVersion(
                                configuration.providerType(), configuration.baseUrl(), configuration.modelId())
                        : null);
    }

    @Transactional
    public AIProviderSettingsResponse putAIProvider(AIProviderUpdateRequest request) {
        vaultService.requireUnlocked();
        String providerType = toDatabaseProvider(request.provider());
        String baseUrl = providerType.equals("OPENAI") ? null : validateBaseUrl(request.baseUrl());
        String model = request.model().trim();
        if (model.isEmpty()) {
            throw new SettingsRequestException("Enter a model name.", 400);
        }

        Optional<AIProviderConfiguration> existing = findAIProvider();
        String submittedKey = request.apiKey();
        if (submittedKey == null || submittedKey.isBlank()) {
            if (existing.isEmpty()) {
                throw new SettingsRequestException("Enter an API key to configure the provider.", 400);
            }
            jdbcTemplate.update(
                    """
                    UPDATE ai_provider_configuration
                    SET provider_type = ?, base_url = ?, model_id = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE singleton_id = 1
                    """,
                    providerType,
                    baseUrl,
                    model);
        } else {
            byte[] plaintext = submittedKey.trim().getBytes(StandardCharsets.UTF_8);
            if (plaintext.length < 8 || plaintext.length > 4096) {
                Arrays.fill(plaintext, (byte) 0);
                throw new SettingsRequestException("The API key length is invalid.", 400);
            }
            EncryptedPayload encrypted = null;
            byte[] nonce = null;
            byte[] ciphertext = null;
            try {
                encrypted = vaultService.encryptLocal(plaintext, API_KEY_AAD);
                nonce = encrypted.nonce();
                ciphertext = encrypted.ciphertext();
                jdbcTemplate.update(
                        """
                        INSERT INTO ai_provider_configuration
                            (singleton_id, provider_type, base_url, model_id, api_key_nonce, api_key_ciphertext, updated_at)
                        VALUES (1, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        ON CONFLICT (singleton_id) DO UPDATE SET
                            provider_type = EXCLUDED.provider_type,
                            base_url = EXCLUDED.base_url,
                            model_id = EXCLUDED.model_id,
                            api_key_nonce = EXCLUDED.api_key_nonce,
                            api_key_ciphertext = EXCLUDED.api_key_ciphertext,
                            updated_at = CURRENT_TIMESTAMP
                        """,
                        providerType,
                        baseUrl,
                        model,
                        nonce,
                        ciphertext);
            } finally {
                Arrays.fill(plaintext, (byte) 0);
                if (nonce != null) Arrays.fill(nonce, (byte) 0);
                if (ciphertext != null) Arrays.fill(ciphertext, (byte) 0);
                if (encrypted != null) encrypted.destroy();
            }
        }
        return getAIProvider();
    }

    @Transactional
    public void deleteAIProvider() {
        vaultService.requireUnlocked();
        jdbcTemplate.update("DELETE FROM ai_provider_configuration WHERE singleton_id = 1");
    }

    @Transactional(readOnly = true)
    public int getBodyCacheDays() {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT body_cache_ttl_days FROM app_user WHERE singleton_id = 1",
                    Integer.class);
        } catch (EmptyResultDataAccessException exception) {
            throw new SettingsRequestException("Sign in with Google before changing local settings.", 409);
        }
    }

    @Transactional
    public int updateBodyCacheDays(int days) {
        vaultService.requireUnlocked();
        int updated = jdbcTemplate.update(
                "UPDATE app_user SET body_cache_ttl_days = ?, updated_at = CURRENT_TIMESTAMP WHERE singleton_id = 1",
                days);
        if (updated != 1) {
            throw new SettingsRequestException("Sign in with Google before changing local settings.", 409);
        }
        jdbcTemplate.update("""
                UPDATE mail_message
                SET body_expires_at = body_cached_at + (? * INTERVAL '1 day')
                WHERE body_ciphertext IS NOT NULL
                  AND body_cached_at IS NOT NULL
                  AND body_expires_at > CURRENT_TIMESTAMP
                """, days);
        jdbcTemplate.update("""
                UPDATE mail_thread mt
                SET snippet_nonce = NULL, snippet_ciphertext = NULL, snippet_expires_at = NULL
                WHERE mt.snippet_ciphertext IS NOT NULL
                  AND mt.snippet_expires_at > CURRENT_TIMESTAMP
                  AND NOT EXISTS (
                      SELECT 1 FROM mail_message mm
                      WHERE mm.thread_id = mt.id AND mm.body_cached_at IS NOT NULL
                  )
                """);
        jdbcTemplate.update("""
                UPDATE mail_thread mt
                SET snippet_expires_at = (
                    SELECT MAX(mm.body_cached_at) + (? * INTERVAL '1 day')
                    FROM mail_message mm
                    WHERE mm.thread_id = mt.id AND mm.body_cached_at IS NOT NULL
                )
                WHERE mt.snippet_ciphertext IS NOT NULL
                  AND mt.snippet_expires_at > CURRENT_TIMESTAMP
                """, days);
        jdbcTemplate.update("""
                UPDATE mail_attachment
                SET expires_at = fetched_at + (? * INTERVAL '1 day')
                WHERE content_ciphertext IS NOT NULL
                  AND fetched_at IS NOT NULL
                  AND expires_at > CURRENT_TIMESTAMP
                """, days);
        jdbcTemplate.update("""
                UPDATE assistant_insight ai
                SET expires_at = source_expiry.expires_at
                FROM (
                    SELECT ais.insight_id, MIN(mm.body_expires_at) AS expires_at
                    FROM assistant_insight_source ais
                    JOIN mail_message mm ON mm.id = ais.message_id
                    WHERE mm.body_expires_at IS NOT NULL
                    GROUP BY ais.insight_id
                ) source_expiry
                WHERE ai.id = source_expiry.insight_id
                """);
        jdbcTemplate.update("""
                UPDATE summary_run sr
                SET expires_at = run_expiry.expires_at
                FROM (
                    SELECT source_rows.summary_run_id, MIN(source_rows.expires_at) AS expires_at
                    FROM (
                        SELECT srs.summary_run_id, mm.body_expires_at AS expires_at
                        FROM summary_run_source srs
                        JOIN mail_message mm ON mm.id = srs.message_id
                        WHERE mm.body_expires_at IS NOT NULL
                        UNION ALL
                        SELECT ai.summary_run_id, mm.body_expires_at AS expires_at
                        FROM assistant_insight ai
                        JOIN assistant_insight_source ais ON ais.insight_id = ai.id
                        JOIN mail_message mm ON mm.id = ais.message_id
                        WHERE mm.body_expires_at IS NOT NULL
                    ) source_rows
                    GROUP BY source_rows.summary_run_id
                ) run_expiry
                WHERE sr.id = run_expiry.summary_run_id
                  AND sr.narrative_ciphertext IS NOT NULL
                """);
        jdbcTemplate.update("""
                UPDATE summary_run sr
                SET expires_at = sr.created_at + (? * INTERVAL '1 day')
                WHERE sr.narrative_ciphertext IS NOT NULL
                  AND sr.run_purpose IN ('FIRST_LINK', 'MANUAL', 'DAILY', 'CATCH_UP')
                  AND NOT EXISTS (
                      SELECT 1 FROM summary_run_source srs WHERE srs.summary_run_id = sr.id
                  )
                """, days);
        return days;
    }

    private Optional<AIProviderConfiguration> findAIProvider() {
        List<AIProviderConfiguration> rows = jdbcTemplate.query(
                """
                SELECT provider_type, base_url, model_id, (api_key_ciphertext IS NOT NULL) AS key_configured
                FROM ai_provider_configuration
                WHERE singleton_id = 1
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> new AIProviderConfiguration(
                        resultSet.getString("provider_type"),
                        resultSet.getString("base_url"),
                        resultSet.getString("model_id"),
                        resultSet.getBoolean("key_configured")));
        return rows.stream().findFirst();
    }

    private static String toDatabaseProvider(String provider) {
        return switch (provider == null ? "" : provider.trim().toLowerCase()) {
            case "openai" -> "OPENAI";
            case "openai-compatible" -> "OPENAI_COMPATIBLE";
            default -> throw new SettingsRequestException("Select a supported AI provider.", 400);
        };
    }

    private static String toApiProvider(String provider) {
        return switch (provider) {
            case "OPENAI" -> "openai";
            case "OPENAI_COMPATIBLE" -> "openai-compatible";
            default -> throw new IllegalStateException("Unknown stored AI provider type.");
        };
    }

    private static String validateBaseUrl(String value) {
        if (value == null || value.isBlank()) {
            throw new SettingsRequestException("Enter an OpenAI-compatible endpoint URL.", 400);
        }
        try {
            URI uri = URI.create(value.trim());
            String host = uri.getHost();
            boolean localHttp = "http".equalsIgnoreCase(uri.getScheme())
                    && host != null
                    && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("[::1]"));
            boolean secureHttps = "https".equalsIgnoreCase(uri.getScheme()) && host != null;
            if ((!localHttp && !secureHttps)
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
            return uri.toString().replaceAll("/$", "");
        } catch (IllegalArgumentException exception) {
            throw new SettingsRequestException(
                    "Use an HTTPS endpoint, or HTTP on localhost, without credentials, query, or fragment.", 400);
        }
    }

    public static final class SettingsRequestException extends RuntimeException {
        private final int statusCode;

        public SettingsRequestException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }
}

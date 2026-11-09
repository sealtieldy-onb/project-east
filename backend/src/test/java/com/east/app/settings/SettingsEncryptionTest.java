package com.east.app.settings;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.east.app.vault.EncryptedPayload;
import com.east.app.vault.LocalVaultKeyStore;
import com.east.app.vault.VaultCryptoService;
import com.east.app.vault.VaultRecord;
import com.east.app.vault.VaultRepository;
import com.east.app.vault.VaultService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class SettingsEncryptionTest {

    private static final byte[] API_KEY_AAD = "east-ai-provider-key:v1".getBytes(StandardCharsets.US_ASCII);

    @TempDir
    Path tempDir;

    @Test
    void aiProviderKeyIsEncryptedBeforePersistence() {
        MemoryVaultRepository repository = new MemoryVaultRepository();
        LocalVaultKeyStore keyStore = new LocalVaultKeyStore(tempDir.resolve("east").resolve("vault.key").toString());
        VaultCryptoService crypto = new VaultCryptoService();
        VaultService vault = new VaultService(repository, crypto, null, keyStore);
        vault.initializeForLocalUse();

        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        SettingsService settings = new SettingsService(jdbc, vault);
        String syntheticApiKey = "test-only-provider-key-93e1";
        byte[] expectedPlaintext = syntheticApiKey.getBytes(StandardCharsets.UTF_8);
        EncryptedPayload savedPayload = null;
        byte[] decrypted = null;

        try {
            settings.putAIProvider(new AIProviderUpdateRequest("openai", null, "test-model", syntheticApiKey));
            UpdateCall insert = jdbc.updates.stream()
                    .filter(call -> call.sql().contains("INSERT INTO ai_provider_configuration"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("OPENAI", insert.arguments()[0]);
            assertEquals("test-model", insert.arguments()[2]);
            assertTrue(insert.arguments()[3] instanceof byte[]);
            assertTrue(insert.arguments()[4] instanceof byte[]);

            byte[] nonce = (byte[]) insert.arguments()[3];
            byte[] ciphertext = (byte[]) insert.arguments()[4];
            assertEquals(12, nonce.length);
            assertEquals(expectedPlaintext.length + 16, ciphertext.length);
            assertFalse(contains(ciphertext, expectedPlaintext));

            savedPayload = new EncryptedPayload(nonce, ciphertext);
            decrypted = vault.decryptLocal(savedPayload, API_KEY_AAD);
            assertArrayEquals(expectedPlaintext, decrypted);
        } finally {
            if (savedPayload != null) savedPayload.destroy();
            if (decrypted != null) Arrays.fill(decrypted, (byte) 0);
            Arrays.fill(expectedPlaintext, (byte) 0);
            jdbc.clearCapturedBinaryArguments();
        }
    }

    private static boolean contains(byte[] value, byte[] sequence) {
        if (sequence.length == 0 || sequence.length > value.length) return false;
        for (int start = 0; start <= value.length - sequence.length; start++) {
            int offset = 0;
            while (offset < sequence.length && value[start + offset] == sequence[offset]) offset++;
            if (offset == sequence.length) return true;
        }
        return false;
    }

    private record UpdateCall(String sql, Object[] arguments) { }

    private static final class CapturingJdbcTemplate extends JdbcTemplate {
        private final List<UpdateCall> updates = new ArrayList<>();

        @Override
        public int update(String sql, Object... arguments) {
            Object[] copied = Arrays.stream(arguments)
                    .map(argument -> argument instanceof byte[] bytes ? Arrays.copyOf(bytes, bytes.length) : argument)
                    .toArray();
            updates.add(new UpdateCall(sql, copied));
            return 1;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper) {
            return List.of();
        }

        private void clearCapturedBinaryArguments() {
            for (UpdateCall call : updates) {
                for (Object argument : call.arguments()) {
                    if (argument instanceof byte[] bytes) Arrays.fill(bytes, (byte) 0);
                }
            }
            updates.clear();
        }
    }

    private static final class MemoryVaultRepository extends VaultRepository {
        private VaultRecord stored;

        private MemoryVaultRepository() {
            super(null);
        }

        @Override
        public synchronized Optional<VaultRecord> find() {
            if (stored == null) return Optional.empty();
            return Optional.of(new VaultRecord(stored.salt(), stored.encryptedVerificationMarker()));
        }

        @Override
        public synchronized boolean isInitialized() {
            return stored != null;
        }

        @Override
        public synchronized void create(byte[] salt, byte[] encryptedVerificationMarker) {
            if (stored != null) throw new DuplicateKeyException("Test vault already exists");
            stored = new VaultRecord(salt, encryptedVerificationMarker);
        }
    }
}

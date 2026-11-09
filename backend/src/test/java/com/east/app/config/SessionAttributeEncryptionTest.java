package com.east.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.east.app.auth.EastUserPrincipal;
import com.east.app.vault.EncryptedPayload;
import com.east.app.vault.VaultCryptoService;
import com.east.app.vault.VaultService;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.ConversionService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

class SessionAttributeEncryptionTest {

    private static final String SYNTHETIC_EMAIL = "session-encryption@example.invalid";
    private static final String SYNTHETIC_SUBJECT = "synthetic-session-encryption-subject";
    private static final String SYNTHETIC_NONCE = "synthetic-oauth-nonce-do-not-store-in-plaintext";

    @Test
    void encryptsSerializedSecurityContextAndOAuthNonceAndRoundTripsThem() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x51);
        TestVault vault = new TestVault(key);
        ConversionService conversion = new SessionAttributeEncryptionConfiguration()
                .springSessionConversionService(vault, new VaultCryptoService());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                new EastUserPrincipal(
                        UUID.fromString("20000000-0000-4000-8000-000000000051"),
                        SYNTHETIC_SUBJECT,
                        SYNTHETIC_EMAIL,
                        "Synthetic test identity"),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        byte[] encryptedContext = conversion.convert(context, byte[].class);
        byte[] encryptedNonce = conversion.convert(SYNTHETIC_NONCE, byte[].class);

        try {
            assertTrue(encryptedContext.length > 40);
            assertFalse(contains(encryptedContext, SYNTHETIC_EMAIL.getBytes(StandardCharsets.UTF_8)));
            assertFalse(contains(encryptedContext, SYNTHETIC_SUBJECT.getBytes(StandardCharsets.UTF_8)));
            assertFalse(contains(encryptedNonce, SYNTHETIC_NONCE.getBytes(StandardCharsets.UTF_8)));

            SecurityContext restored = assertInstanceOf(
                    SecurityContext.class, conversion.convert(encryptedContext, Object.class));
            EastUserPrincipal principal = assertInstanceOf(
                    EastUserPrincipal.class, restored.getAuthentication().getPrincipal());
            assertEquals(SYNTHETIC_EMAIL, principal.email());
            assertEquals(SYNTHETIC_SUBJECT, principal.googleSubject());
            assertEquals(SYNTHETIC_NONCE, conversion.convert(encryptedNonce, Object.class));
        } finally {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(encryptedContext, (byte) 0);
            Arrays.fill(encryptedNonce, (byte) 0);
        }
    }

    @Test
    void unavailableKeyAndTamperedEnvelopeAreTreatedAsAbsentAttributes() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x62);
        TestVault vault = new TestVault(key);
        ConversionService conversion = new SessionAttributeEncryptionConfiguration()
                .springSessionConversionService(vault, new VaultCryptoService());
        byte[] encrypted = conversion.convert("synthetic-session-value", byte[].class);
        byte[] tampered = encrypted.clone();
        tampered[tampered.length - 1] ^= 0x01;

        try {
            vault.setLocked(true);
            assertNull(conversion.convert(encrypted, Object.class));
            vault.setLocked(false);
            assertNull(conversion.convert(tampered, Object.class));
            assertNull(conversion.convert("legacy-plaintext-session-value".getBytes(StandardCharsets.UTF_8), Object.class));
        } finally {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(encrypted, (byte) 0);
            Arrays.fill(tampered, (byte) 0);
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

    private static final class TestVault extends VaultService {
        private final VaultCryptoService crypto = new VaultCryptoService();
        private final byte[] key;
        private boolean locked;

        private TestVault(byte[] key) {
            super(null, new VaultCryptoService(), null, null);
            this.key = key;
        }

        private void setLocked(boolean locked) {
            this.locked = locked;
        }

        @Override
        public synchronized EncryptedPayload encryptLocal(byte[] plaintext, byte[] associatedData) {
            if (locked) throw new VaultLockedException();
            return crypto.encrypt(key, plaintext, associatedData);
        }

        @Override
        public synchronized byte[] decryptLocal(EncryptedPayload payload, byte[] associatedData) {
            if (locked) throw new VaultLockedException();
            return crypto.decrypt(key, payload, associatedData);
        }
    }
}

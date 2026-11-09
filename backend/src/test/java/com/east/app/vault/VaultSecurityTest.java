package com.east.app.vault;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DuplicateKeyException;

class VaultSecurityTest {

    @TempDir
    Path tempDir;

    @Test
    void encryptedPayloadRoundTripsAndAuthenticatesAssociatedData() {
        VaultCryptoService crypto = new VaultCryptoService();
        byte[] key = new byte[LocalVaultKeyStore.KEY_BYTES];
        new SecureRandom().nextBytes(key);
        byte[] plaintext = "test-only vault payload".getBytes(StandardCharsets.UTF_8);
        byte[] associatedData = "owner:mailbox:test".getBytes(StandardCharsets.UTF_8);
        EncryptedPayload payload = crypto.encrypt(key, plaintext, associatedData);
        byte[] ciphertext = null;
        byte[] packed = null;
        byte[] decrypted = null;

        try {
            ciphertext = payload.ciphertext();
            packed = crypto.pack(payload);
            assertEquals(plaintext.length + 16, ciphertext.length);
            assertFalse(contains(packed, plaintext));
            decrypted = crypto.decrypt(key, payload, associatedData);
            assertArrayEquals(plaintext, decrypted);
            assertThrows(
                    VaultCryptoService.InvalidVaultCiphertextException.class,
                    () -> crypto.decrypt(key, payload, "owner:other-mailbox:test".getBytes(StandardCharsets.UTF_8)));

            ciphertext[0] ^= 0x01;
            EncryptedPayload tampered = new EncryptedPayload(payload.nonce(), ciphertext);
            try {
                assertThrows(
                        VaultCryptoService.InvalidVaultCiphertextException.class,
                        () -> crypto.decrypt(key, tampered, associatedData));
            } finally {
                tampered.destroy();
                Arrays.fill(ciphertext, (byte) 0);
            }
        } finally {
            payload.destroy();
            if (ciphertext != null) Arrays.fill(ciphertext, (byte) 0);
            if (packed != null) Arrays.fill(packed, (byte) 0);
            if (decrypted != null) Arrays.fill(decrypted, (byte) 0);
            Arrays.fill(key, (byte) 0);
            Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(associatedData, (byte) 0);
        }
    }

    @Test
    void randomLocalKeyUnlocksAutomaticallyAfterServiceReinitialization() {
        Path keyFile = tempDir.resolve("east").resolve("vault.key");
        LocalVaultKeyStore keyStore = new LocalVaultKeyStore(keyFile.toString());
        MemoryVaultRepository repository = new MemoryVaultRepository();
        byte[] plaintext = "persisted test data".getBytes(StandardCharsets.UTF_8);
        byte[] associatedData = "owner:mailbox:thread".getBytes(StandardCharsets.UTF_8);
        VaultService firstRun = new VaultService(repository, new VaultCryptoService(), null, keyStore);
        EncryptedPayload encrypted = null;
        byte[] decrypted = null;

        try {
            assertTrue(keyStore.read().isEmpty());
            firstRun.initializeForLocalUse();
            assertTrue(firstRun.isUnlocked());
            assertTrue(Files.exists(keyFile));
            encrypted = firstRun.encryptLocal(plaintext, associatedData);

            VaultService afterRestart = new VaultService(
                    repository,
                    new VaultCryptoService(),
                    null,
                    new LocalVaultKeyStore(keyFile.toString()));
            afterRestart.initializeForLocalUse();

            assertTrue(afterRestart.isUnlocked());
            decrypted = afterRestart.decryptLocal(encrypted, associatedData);
            assertArrayEquals(plaintext, decrypted);
            byte[] storedKey = keyStore.read().orElseThrow();
            try {
                assertEquals(LocalVaultKeyStore.KEY_BYTES, storedKey.length);
            } finally {
                Arrays.fill(storedKey, (byte) 0);
            }
        } finally {
            if (encrypted != null) encrypted.destroy();
            if (decrypted != null) Arrays.fill(decrypted, (byte) 0);
            Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(associatedData, (byte) 0);
        }
    }

    @Test
    void keyStoreUsesOwnerOnlyPosixPermissions() throws IOException {
        Path keyFile = tempDir.resolve("east").resolve("vault.key");
        LocalVaultKeyStore keyStore = new LocalVaultKeyStore(keyFile.toString());
        byte[] key = new byte[LocalVaultKeyStore.KEY_BYTES];
        new SecureRandom().nextBytes(key);
        try {
            keyStore.write(key);
            if (Files.getFileStore(keyFile).supportsFileAttributeView("posix")) {
                assertEquals(
                        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                        Files.getPosixFilePermissions(keyFile));
                assertEquals(
                        EnumSet.of(
                                PosixFilePermission.OWNER_READ,
                                PosixFilePermission.OWNER_WRITE,
                                PosixFilePermission.OWNER_EXECUTE),
                        Files.getPosixFilePermissions(keyFile.getParent()));
            }
            byte[] readBack = keyStore.read().orElseThrow();
            try {
                assertArrayEquals(key, readBack);
            } finally {
                Arrays.fill(readBack, (byte) 0);
            }
        } finally {
            Arrays.fill(key, (byte) 0);
            keyStore.delete();
        }
    }

    @Test
    void readingExistingKeyRepairsOwnerOnlyParentAndFilePermissions() throws IOException {
        Path parent = tempDir.resolve("existing-east");
        Files.createDirectories(parent);
        Assumptions.assumeTrue(Files.getFileStore(parent).supportsFileAttributeView("posix"));

        Path keyFile = parent.resolve("vault.key");
        byte[] key = new byte[LocalVaultKeyStore.KEY_BYTES];
        new SecureRandom().nextBytes(key);
        Files.write(keyFile, key);
        Files.setPosixFilePermissions(parent, EnumSet.allOf(PosixFilePermission.class));
        Files.setPosixFilePermissions(keyFile, EnumSet.allOf(PosixFilePermission.class));
        LocalVaultKeyStore keyStore = new LocalVaultKeyStore(keyFile.toString());

        try {
            byte[] readBack = keyStore.read().orElseThrow();
            try {
                assertArrayEquals(key, readBack);
            } finally {
                Arrays.fill(readBack, (byte) 0);
            }
            assertEquals(
                    EnumSet.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE),
                    Files.getPosixFilePermissions(parent));
            assertEquals(
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(keyFile));
        } finally {
            Arrays.fill(key, (byte) 0);
            keyStore.delete();
        }
    }

    @Test
    void keyStoreRejectsSymbolicLinkParentOnReadAndDelete() throws IOException {
        Path realParent = tempDir.resolve("real-east");
        Files.createDirectories(realParent);
        Path linkedParent = tempDir.resolve("linked-east");
        try {
            Files.createSymbolicLink(linkedParent, realParent);
        } catch (UnsupportedOperationException exception) {
            Assumptions.abort("Symbolic links are not supported by this file system.");
        }

        LocalVaultKeyStore keyStore = new LocalVaultKeyStore(linkedParent.resolve("vault.key").toString());
        assertThrows(LocalVaultKeyStore.LocalVaultKeyException.class, keyStore::read);
        assertThrows(LocalVaultKeyStore.LocalVaultKeyException.class, keyStore::delete);
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

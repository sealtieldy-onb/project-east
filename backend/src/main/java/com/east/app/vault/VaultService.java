package com.east.app.vault;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Optional;

import jakarta.annotation.PostConstruct;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class VaultService {

    private static final byte[] VERIFICATION_MARKER =
            "east-local-vault-verification-v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] VERIFICATION_AAD =
            "east-vault-marker:v1".getBytes(StandardCharsets.US_ASCII);
    private static final int SALT_BYTES = 16;
    private static final int DERIVED_KEY_BYTES = LocalVaultKeyStore.KEY_BYTES;
    private static final int ARGON_MEMORY_KIB = 64 * 1024;
    private static final int ARGON_ITERATIONS = 3;
    private static final int ARGON_PARALLELISM = 4;

    private final VaultRepository vaultRepository;
    private final VaultCryptoService cryptoService;
    private final LocalDataResetService resetService;
    private final LocalVaultKeyStore keyStore;
    private final SecureRandom secureRandom = new SecureRandom();
    private byte[] unlockedKey;

    public VaultService(
            VaultRepository vaultRepository,
            VaultCryptoService cryptoService,
            LocalDataResetService resetService,
            LocalVaultKeyStore keyStore) {
        this.vaultRepository = vaultRepository;
        this.cryptoService = cryptoService;
        this.resetService = resetService;
        this.keyStore = keyStore;
    }

    /** Creates a local key for a new installation or loads the key for existing local data. */
    @PostConstruct
    public synchronized void initializeForLocalUse() {
        Optional<VaultRecord> savedRecord = vaultRepository.find();
        if (savedRecord.isEmpty()) {
            initializeNewVault();
            return;
        }

        VaultRecord record = savedRecord.get();
        byte[] key;
        try {
            key = keyStore.read().orElse(null);
        } catch (LocalVaultKeyStore.LocalVaultKeyException exception) {
            record.destroy();
            return;
        }
        if (key == null) {
            record.destroy();
            return;
        }

        byte[] packedMarker = record.encryptedVerificationMarker();
        byte[] marker = null;
        EncryptedPayload encryptedMarker = null;
        try {
            encryptedMarker = cryptoService.unpack(packedMarker);
            marker = cryptoService.decrypt(key, encryptedMarker, VERIFICATION_AAD);
            if (MessageDigest.isEqual(marker, VERIFICATION_MARKER)) {
                replaceUnlockedKey(key);
                key = null;
            }
        } catch (VaultCryptoService.InvalidVaultCiphertextException ignored) {
            // A legacy vault needs a one-time passphrase migration; a bad key file is never trusted.
        } finally {
            record.destroy();
            wipe(key);
            wipe(packedMarker);
            wipe(marker);
            if (encryptedMarker != null) encryptedMarker.destroy();
        }
    }

    public synchronized VaultStatusResponse status() {
        boolean initialized = vaultRepository.isInitialized();
        return new VaultStatusResponse(initialized, unlockedKey != null);
    }

    /** One-time transition for an existing passphrase-derived vault. */
    public synchronized VaultStatusResponse unlock(String passphrase) {
        Optional<VaultRecord> savedRecord = vaultRepository.find();
        if (savedRecord.isEmpty()) {
            throw new VaultRequestException("The local vault has not been initialized.", 409);
        }

        VaultRecord record = savedRecord.get();
        byte[] salt = record.salt();
        byte[] packedMarker = record.encryptedVerificationMarker();
        byte[] key = null;
        byte[] marker = null;
        EncryptedPayload encryptedMarker = null;
        try {
            key = deriveKey(passphrase, salt);
            encryptedMarker = cryptoService.unpack(packedMarker);
            marker = cryptoService.decrypt(key, encryptedMarker, VERIFICATION_AAD);
            if (!MessageDigest.isEqual(marker, VERIFICATION_MARKER)) {
                throw new InvalidPassphraseException();
            }
            // Persist only the random derived key, never the passphrase, so later starts need no prompt.
            keyStore.write(key);
            replaceUnlockedKey(key);
            key = null;
            return new VaultStatusResponse(true, true);
        } catch (VaultCryptoService.InvalidVaultCiphertextException exception) {
            throw new InvalidPassphraseException();
        } finally {
            record.destroy();
            wipe(salt);
            wipe(packedMarker);
            if (encryptedMarker != null) encryptedMarker.destroy();
            wipe(key);
            wipe(marker);
        }
    }

    public synchronized boolean isUnlocked() {
        return unlockedKey != null;
    }

    public synchronized void requireUnlocked() {
        if (unlockedKey == null) {
            throw new VaultLockedException();
        }
    }

    /** Encrypts content for local persistence. The caller owns the returned payload. */
    public synchronized EncryptedPayload encryptLocal(byte[] plaintext, byte[] associatedData) {
        requireUnlocked();
        return cryptoService.encrypt(unlockedKey, plaintext, associatedData);
    }

    public synchronized byte[] decryptLocal(EncryptedPayload payload, byte[] associatedData) {
        requireUnlocked();
        return cryptoService.decrypt(unlockedKey, payload, associatedData);
    }

    public synchronized void reset(String confirmation) {
        if (!"RESET LOCAL DATA".equals(confirmation)) {
            throw new VaultRequestException("Type RESET LOCAL DATA exactly to erase local data.", 400);
        }
        clearUnlockedKey();
        resetService.resetApplicationData();
        keyStore.delete();
        initializeNewVault();
    }

    private void initializeNewVault() {
        byte[] key = null;
        byte[] salt = new byte[SALT_BYTES];
        byte[] packedMarker = null;
        EncryptedPayload encryptedMarker = null;
        try {
            Optional<byte[]> storedKey = keyStore.read();
            if (storedKey.isPresent()) {
                key = storedKey.get();
            } else {
                key = new byte[DERIVED_KEY_BYTES];
                secureRandom.nextBytes(key);
                // Write first so a crash before the database commit can safely resume initialization.
                keyStore.write(key);
            }
            secureRandom.nextBytes(salt);
            encryptedMarker = cryptoService.encrypt(key, VERIFICATION_MARKER, VERIFICATION_AAD);
            packedMarker = cryptoService.pack(encryptedMarker);
            vaultRepository.create(salt, packedMarker);
            replaceUnlockedKey(key);
            key = null;
        } catch (DuplicateKeyException exception) {
            throw new VaultRequestException("The local vault was initialized concurrently. Restart East.", 409);
        } finally {
            wipe(salt);
            wipe(packedMarker);
            if (encryptedMarker != null) encryptedMarker.destroy();
            wipe(key);
        }
    }

    private byte[] deriveKey(String passphrase, byte[] salt) {
        byte[] passwordBytes = passphrase.getBytes(StandardCharsets.UTF_8);
        byte[] key = new byte[DERIVED_KEY_BYTES];
        try {
            Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withSalt(salt)
                    .withMemoryAsKB(ARGON_MEMORY_KIB)
                    .withIterations(ARGON_ITERATIONS)
                    .withParallelism(ARGON_PARALLELISM)
                    .build();
            Argon2BytesGenerator generator = new Argon2BytesGenerator();
            generator.init(parameters);
            generator.generateBytes(passwordBytes, key);
            return key;
        } catch (RuntimeException exception) {
            wipe(key);
            throw exception;
        } finally {
            wipe(passwordBytes);
        }
    }

    private void replaceUnlockedKey(byte[] key) {
        clearUnlockedKey();
        unlockedKey = key;
    }

    private void clearUnlockedKey() {
        wipe(unlockedKey);
        unlockedKey = null;
    }

    private static void wipe(byte[] value) {
        if (value != null) Arrays.fill(value, (byte) 0);
    }

    public static final class VaultRequestException extends RuntimeException {
        private final int statusCode;

        public VaultRequestException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }

    public static final class InvalidPassphraseException extends RuntimeException {
        public InvalidPassphraseException() {
            super("The current passphrase could not migrate the local vault.");
        }
    }

    public static final class VaultLockedException extends RuntimeException {
        public VaultLockedException() {
            super("The local vault needs a one-time key migration.");
        }
    }
}

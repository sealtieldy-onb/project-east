package com.east.app.vault;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

@Service
public class VaultCryptoService {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();

    public EncryptedPayload encrypt(byte[] key, byte[] plaintext, byte[] associatedData) {
        byte[] nonce = new byte[NONCE_BYTES];
        byte[] ciphertext = null;
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData);
            ciphertext = cipher.doFinal(plaintext);
            return new EncryptedPayload(nonce, ciphertext);
        } catch (GeneralSecurityException exception) {
            throw new VaultCryptoException("Unable to encrypt local data", exception);
        } finally {
            wipe(nonce);
            wipe(ciphertext);
        }
    }

    public byte[] decrypt(byte[] key, EncryptedPayload payload, byte[] associatedData) {
        byte[] nonce = payload.nonce();
        byte[] ciphertext = payload.ciphertext();
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData);
            return cipher.doFinal(ciphertext);
        } catch (AEADBadTagException exception) {
            throw new InvalidVaultCiphertextException();
        } catch (GeneralSecurityException exception) {
            throw new VaultCryptoException("Unable to decrypt local data", exception);
        } finally {
            wipe(nonce);
            wipe(ciphertext);
        }
    }

    public byte[] pack(EncryptedPayload payload) {
        byte[] nonce = payload.nonce();
        byte[] ciphertext = payload.ciphertext();
        try {
            byte[] packed = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, packed, 0, nonce.length);
            System.arraycopy(ciphertext, 0, packed, nonce.length, ciphertext.length);
            return packed;
        } finally {
            wipe(nonce);
            wipe(ciphertext);
        }
    }

    public EncryptedPayload unpack(byte[] packed) {
        if (packed == null || packed.length <= NONCE_BYTES) {
            throw new InvalidVaultCiphertextException();
        }
        byte[] nonce = new byte[NONCE_BYTES];
        byte[] ciphertext = new byte[packed.length - NONCE_BYTES];
        System.arraycopy(packed, 0, nonce, 0, NONCE_BYTES);
        System.arraycopy(packed, NONCE_BYTES, ciphertext, 0, ciphertext.length);
        try {
            return new EncryptedPayload(nonce, ciphertext);
        } finally {
            wipe(nonce);
            wipe(ciphertext);
        }
    }

    private static void wipe(byte[] value) {
        if (value != null) {
            java.util.Arrays.fill(value, (byte) 0);
        }
    }

    public static final class InvalidVaultCiphertextException extends RuntimeException {
        public InvalidVaultCiphertextException() {
            super("Encrypted local data could not be authenticated");
        }
    }

    public static final class VaultCryptoException extends RuntimeException {
        public VaultCryptoException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

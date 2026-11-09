package com.east.app.vault;

import java.util.Arrays;

public record EncryptedPayload(byte[] nonce, byte[] ciphertext) {

    public EncryptedPayload {
        nonce = Arrays.copyOf(nonce, nonce.length);
        ciphertext = Arrays.copyOf(ciphertext, ciphertext.length);
    }

    @Override
    public byte[] nonce() {
        return Arrays.copyOf(nonce, nonce.length);
    }

    @Override
    public byte[] ciphertext() {
        return Arrays.copyOf(ciphertext, ciphertext.length);
    }

    /** Wipes the backing buffers after their persistence operation is complete. */
    public void destroy() {
        Arrays.fill(nonce, (byte) 0);
        Arrays.fill(ciphertext, (byte) 0);
    }
}

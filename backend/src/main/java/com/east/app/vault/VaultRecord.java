package com.east.app.vault;

import java.util.Arrays;

public record VaultRecord(byte[] salt, byte[] encryptedVerificationMarker) {

    public VaultRecord {
        salt = Arrays.copyOf(salt, salt.length);
        encryptedVerificationMarker = Arrays.copyOf(encryptedVerificationMarker, encryptedVerificationMarker.length);
    }

    @Override
    public byte[] salt() {
        return Arrays.copyOf(salt, salt.length);
    }

    @Override
    public byte[] encryptedVerificationMarker() {
        return Arrays.copyOf(encryptedVerificationMarker, encryptedVerificationMarker.length);
    }

    public void destroy() {
        Arrays.fill(salt, (byte) 0);
        Arrays.fill(encryptedVerificationMarker, (byte) 0);
    }
}

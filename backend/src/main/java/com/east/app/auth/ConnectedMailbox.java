package com.east.app.auth;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

record ConnectedMailbox(
        UUID id,
        UUID appUserId,
        String googleSubject,
        String email,
        String displayName,
        int colorIndex,
        boolean reauthorizationRequired,
        List<String> grantedScopes,
        byte[] refreshTokenNonce,
        byte[] refreshTokenCiphertext,
        Instant lastSyncAt,
        Instant createdAt,
        Instant updatedAt) {

    ConnectedMailbox {
        grantedScopes = List.copyOf(grantedScopes);
        refreshTokenNonce = refreshTokenNonce == null ? null : Arrays.copyOf(refreshTokenNonce, refreshTokenNonce.length);
        refreshTokenCiphertext = refreshTokenCiphertext == null
                ? null
                : Arrays.copyOf(refreshTokenCiphertext, refreshTokenCiphertext.length);
    }

    @Override
    public byte[] refreshTokenNonce() {
        return refreshTokenNonce == null ? null : Arrays.copyOf(refreshTokenNonce, refreshTokenNonce.length);
    }

    @Override
    public byte[] refreshTokenCiphertext() {
        return refreshTokenCiphertext == null
                ? null
                : Arrays.copyOf(refreshTokenCiphertext, refreshTokenCiphertext.length);
    }
}

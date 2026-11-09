package com.east.app.vault;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VaultUnlockRequest(
        @NotBlank(message = "Passphrase is required.")
        @Size(min = 12, max = 1024, message = "Passphrase must contain between 12 and 1024 characters.")
        String passphrase) {

    @Override
    public String toString() {
        return "VaultUnlockRequest[passphrase=REDACTED]";
    }
}

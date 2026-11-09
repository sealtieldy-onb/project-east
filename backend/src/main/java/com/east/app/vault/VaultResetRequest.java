package com.east.app.vault;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VaultResetRequest(
        @NotBlank(message = "Reset confirmation is required.")
        @Size(max = 32, message = "Reset confirmation is too long.")
        String confirmation) {

    @Override
    public String toString() {
        return "VaultResetRequest[confirmation=REDACTED]";
    }
}

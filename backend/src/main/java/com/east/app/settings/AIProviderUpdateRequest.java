package com.east.app.settings;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AIProviderUpdateRequest(
        @NotBlank @Size(max = 32) String provider,
        @Size(max = 2048) String baseUrl,
        @NotBlank @Size(max = 256) String model,
        @Size(max = 4096) String apiKey) {

    @Override
    public String toString() {
        return "AIProviderUpdateRequest[provider=" + provider + ", baseUrl=" + baseUrl
                + ", model=" + model + ", apiKey=REDACTED]";
    }
}

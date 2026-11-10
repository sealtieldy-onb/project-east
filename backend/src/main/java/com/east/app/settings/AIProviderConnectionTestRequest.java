package com.east.app.settings;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Transient provider values used only for one explicit connection-check request. */
public record AIProviderConnectionTestRequest(
        @NotBlank @Pattern(regexp = "openai|openai-compatible") String provider,
        @Size(max = 2048) String baseUrl,
        @NotBlank @Size(max = 256) String model,
        @Size(max = 4096) String apiKey) {

    @Override
    public String toString() {
        return "AIProviderConnectionTestRequest[provider=" + provider + ", baseUrl=" + baseUrl
                + ", model=" + model + ", apiKey=REDACTED]";
    }
}

package com.east.app.settings;

public record AIProviderConfiguration(
        String providerType,
        String baseUrl,
        String modelId,
        boolean keyConfigured) {
}

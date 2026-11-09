package com.east.app.settings;

public record AIProviderSettingsResponse(
        String provider,
        String baseUrl,
        String model,
        boolean keyConfigured) {

    static AIProviderSettingsResponse empty() {
        return new AIProviderSettingsResponse(null, null, null, false);
    }
}

package com.east.app.settings;

public record AIProviderSettingsResponse(
        String provider,
        String baseUrl,
        String model,
        boolean keyConfigured,
        String configurationVersion) {

    static AIProviderSettingsResponse empty() {
        return new AIProviderSettingsResponse(null, null, null, false, null);
    }
}

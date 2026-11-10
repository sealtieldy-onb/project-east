package com.east.app.ai;

/** Explicit-call provider boundary used by report and draft workflows. */
public interface AIProviderGateway {

    AIProviderClient.ProviderMetadata providerMetadata();

    /** Test a caller-selected provider configuration without persisting it. */
    default void testConnection(String providerType, String baseUrl, String modelId, String submittedApiKey) {
        throw new UnsupportedOperationException("Provider connection testing is not supported.");
    }

    <T> AIProviderClient.Completion<T> completeWithMetadata(
            AIProviderPrompt prompt,
            StructuredOutputContract<T> contract,
            String expectedConfigurationVersion);
}

package com.east.app.ai;

/** Explicit-call provider boundary used by report and draft workflows. */
public interface AIProviderGateway {

    AIProviderClient.ProviderMetadata providerMetadata();

    <T> AIProviderClient.Completion<T> completeWithMetadata(
            AIProviderPrompt prompt,
            StructuredOutputContract<T> contract,
            String expectedConfigurationVersion);
}

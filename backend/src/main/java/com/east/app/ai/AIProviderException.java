package com.east.app.ai;

/** Provider failures deliberately exclude endpoint response text, prompts, and credentials. */
public final class AIProviderException extends RuntimeException {

    private final Reason reason;

    AIProviderException(Reason reason) {
        super(messageFor(reason));
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    private static String messageFor(Reason reason) {
        return switch (reason) {
            case NOT_CONFIGURED -> "Configure an AI provider and API key before requesting model processing.";
            case VAULT_UNAVAILABLE -> "The local encryption key is unavailable for model processing.";
            case INVALID_CONFIGURATION -> "The saved AI provider configuration is invalid.";
            case PROVIDER_REJECTED -> "The configured AI provider rejected the request.";
            case PROVIDER_UNAVAILABLE -> "The configured AI provider could not be reached.";
            case INVALID_PROVIDER_RESPONSE -> "The configured AI provider returned an invalid response.";
            case INVALID_MODEL_OUTPUT -> "The model output did not satisfy the required structured format.";
            case DISCLOSURE_CHANGED -> "The AI provider changed since the user approved its disclosure.";
        };
    }

    public enum Reason {
        NOT_CONFIGURED,
        VAULT_UNAVAILABLE,
        INVALID_CONFIGURATION,
        PROVIDER_REJECTED,
        PROVIDER_UNAVAILABLE,
        INVALID_PROVIDER_RESPONSE,
        INVALID_MODEL_OUTPUT,
        DISCLOSURE_CHANGED
    }
}

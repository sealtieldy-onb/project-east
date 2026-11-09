package com.east.app.ai;

/** Safe failure for malformed JSON or a feature contract that rejects model output. */
public final class StructuredOutputValidationException extends RuntimeException {

    public StructuredOutputValidationException() {
        super("The model output did not satisfy the required structured format.");
    }
}

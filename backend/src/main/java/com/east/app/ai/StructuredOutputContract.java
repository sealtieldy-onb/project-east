package com.east.app.ai;

import com.fasterxml.jackson.databind.JsonNode;

/** Supplies the feature-specific JSON Schema and the server-side semantic validator. */
public interface StructuredOutputContract<T> {

    String name();

    JsonNode schema();

    /** Validate fields and product invariants, then return a typed value. */
    T validate(JsonNode output);
}

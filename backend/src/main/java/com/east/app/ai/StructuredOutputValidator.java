package com.east.app.ai;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Strictly parses provider output before any feature-specific contract can consume it. */
@Component
public final class StructuredOutputValidator {

    private static final int MAX_OUTPUT_CHARACTERS = 1_000_000;
    private static final int MAX_SCHEMA_CHARACTERS = 64_000;
    private static final Pattern VALID_SCHEMA_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final JsonFactory STRICT_JSON_FACTORY = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(64)
                    .maxStringLength(MAX_OUTPUT_CHARACTERS)
                    .maxNumberLength(128)
                    .build())
            .build();
    private static final ObjectMapper STRICT_JSON_MAPPER = new ObjectMapper(STRICT_JSON_FACTORY);

    /** Returns a defensive schema copy after checking the adapter's strict root contract. */
    public JsonNode schemaFor(StructuredOutputContract<?> contract) {
        Objects.requireNonNull(contract, "contract");
        String name = contract.name();
        JsonNode schema = contract.schema();
        if (name == null || !VALID_SCHEMA_NAME.matcher(name).matches()
                || schema == null || !schema.isObject()
                || !"object".equals(schema.path("type").asText())
                || !schema.path("additionalProperties").isBoolean()
                || schema.path("additionalProperties").booleanValue()
                || schema.toString().length() > MAX_SCHEMA_CHARACTERS) {
            throw new IllegalArgumentException("The structured-output contract is invalid.");
        }
        return schema.deepCopy();
    }

    public <T> T validate(String json, StructuredOutputContract<T> contract) {
        if (json == null || json.isBlank() || json.length() > MAX_OUTPUT_CHARACTERS) {
            throw new StructuredOutputValidationException();
        }
        schemaFor(contract);

        JsonNode output;
        try (JsonParser parser = STRICT_JSON_FACTORY.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new StructuredOutputValidationException();
            }
            output = STRICT_JSON_MAPPER.readTree(parser);
            if (output == null || !output.isObject() || parser.nextToken() != null) {
                throw new StructuredOutputValidationException();
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw new StructuredOutputValidationException();
        }

        try {
            T value = contract.validate(output.deepCopy());
            if (value == null) {
                throw new StructuredOutputValidationException();
            }
            return value;
        } catch (StructuredOutputValidationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new StructuredOutputValidationException();
        }
    }
}

package com.east.app.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class StructuredOutputValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final StructuredOutputValidator validator = new StructuredOutputValidator();

    @Test
    void parsesACompleteSingleJsonObjectAndDelegatesSemanticValidation() {
        String result = validator.validate("{\"summary\":\"synthetic summary\"}", new SummaryContract());

        assertEquals("synthetic summary", result);
    }

    @Test
    void rejectsDuplicateKeysTrailingJsonAndNonObjectRoots() {
        SummaryContract contract = new SummaryContract();

        assertThrows(StructuredOutputValidationException.class,
                () -> validator.validate("{\"summary\":\"first\",\"summary\":\"second\"}", contract));
        assertThrows(StructuredOutputValidationException.class,
                () -> validator.validate("{\"summary\":\"one\"}{\"summary\":\"two\"}", contract));
        assertThrows(StructuredOutputValidationException.class,
                () -> validator.validate("[\"summary\"]", contract));
    }

    @Test
    void rejectsContractOutputThatViolatesFeatureSemantics() {
        SummaryContract contract = new SummaryContract();

        assertThrows(StructuredOutputValidationException.class,
                () -> validator.validate("{\"summary\":\" \"}", contract));
        assertThrows(StructuredOutputValidationException.class,
                () -> validator.validate("{\"summary\":\"ok\",\"unsupported\":true}", contract));
    }

    @Test
    void requiresStrictObjectSchemaAndReturnsADefensiveSchemaCopy() {
        StoredSchemaContract contract = new StoredSchemaContract();
        JsonNode checkedSchema = validator.schemaFor(contract);

        assertTrue(checkedSchema.path("additionalProperties").isBoolean());
        assertFalse(checkedSchema.path("additionalProperties").booleanValue());
        ((ObjectNode) checkedSchema).put("additionalProperties", true);
        assertFalse(contract.schema.path("additionalProperties").booleanValue());

        assertThrows(IllegalArgumentException.class,
                () -> validator.schemaFor(new InvalidSchemaContract("has spaces")));
        assertThrows(IllegalArgumentException.class,
                () -> validator.schemaFor(new InvalidSchemaContract("invalid_root")));
    }

    private static final class SummaryContract implements StructuredOutputContract<String> {

        @Override
        public String name() {
            return "east_summary";
        }

        @Override
        public JsonNode schema() {
            return strictSummarySchema();
        }

        @Override
        public String validate(JsonNode output) {
            if (output.size() != 1 || !output.path("summary").isTextual()) {
                throw new IllegalArgumentException("Synthetic summary shape is invalid.");
            }
            String summary = output.path("summary").asText();
            if (summary.isBlank() || summary.length() > 160) {
                throw new IllegalArgumentException("Synthetic summary content is invalid.");
            }
            return summary;
        }
    }

    private static final class StoredSchemaContract implements StructuredOutputContract<String> {
        private final ObjectNode schema = strictSummarySchema();

        @Override
        public String name() {
            return "stored_schema";
        }

        @Override
        public JsonNode schema() {
            return schema;
        }

        @Override
        public String validate(JsonNode output) {
            return output.path("summary").asText();
        }
    }

    private record InvalidSchemaContract(String name) implements StructuredOutputContract<String> {

        @Override
        public JsonNode schema() {
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("type", "array");
            schema.put("additionalProperties", false);
            return schema;
        }

        @Override
        public String validate(JsonNode output) {
            return output.asText();
        }
    }

    private static ObjectNode strictSummarySchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("summary").put("type", "string");
        schema.putArray("required").add("summary");
        schema.put("additionalProperties", false);
        return schema;
    }
}

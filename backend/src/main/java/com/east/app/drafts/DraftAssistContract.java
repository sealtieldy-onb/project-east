package com.east.app.drafts;

import com.east.app.ai.StructuredOutputContract;
import com.east.app.ai.StructuredOutputValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;

final class DraftAssistContract implements StructuredOutputContract<DraftAssistContract.Output> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "east_assisted_email_draft";
    }

    @Override
    public JsonNode schema() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        root.putArray("required").add("subject").add("body");
        ObjectNode properties = root.putObject("properties");
        ObjectNode subject = properties.putObject("subject");
        subject.putArray("type").add("string").add("null");
        subject.put("maxLength", 500);
        ObjectNode body = properties.putObject("body");
        body.put("type", "string");
        body.put("minLength", 1);
        body.put("maxLength", 12_000);
        return root;
    }

    @Override
    public Output validate(JsonNode output) {
        if (!output.isObject() || output.size() != 2) throw invalid();
        Set<String> fields = new java.util.HashSet<>();
        output.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(Set.of("subject", "body"))) throw invalid();
        JsonNode subjectNode = output.path("subject");
        String subject = null;
        if (!subjectNode.isNull()) {
            if (!subjectNode.isTextual() || subjectNode.asText().length() > 500
                    || subjectNode.asText().codePoints().anyMatch(Character::isISOControl)) throw invalid();
            subject = subjectNode.asText().trim();
        }
        JsonNode bodyNode = output.path("body");
        if (!bodyNode.isTextual()) throw invalid();
        String body = bodyNode.asText();
        if (body.isBlank() || body.length() > 12_000
                || body.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                        && codePoint != '\n' && codePoint != '\r' && codePoint != '\t')) {
            throw invalid();
        }
        return new Output(subject, body.strip());
    }

    private static StructuredOutputValidationException invalid() {
        return new StructuredOutputValidationException();
    }

    record Output(String subject, String body) { }
}

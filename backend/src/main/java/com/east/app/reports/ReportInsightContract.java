package com.east.app.reports;

import com.east.app.ai.StructuredOutputContract;
import com.east.app.ai.StructuredOutputValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Strict text-only report contract; source tokens are mapped to owned messages by the caller. */
final class ReportInsightContract implements StructuredOutputContract<ReportInsightContract.Output> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_INSIGHTS = 100;
    private final Set<String> allowedSourceTokens;

    ReportInsightContract(Set<String> allowedSourceTokens) {
        this.allowedSourceTokens = Set.copyOf(allowedSourceTokens);
    }

    @Override
    public String name() {
        return "east_mail_report";
    }

    @Override
    public JsonNode schema() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ArrayNode required = root.putArray("required");
        required.add("narrative").add("insights");

        ObjectNode properties = root.putObject("properties");
        ObjectNode narrative = properties.putObject("narrative");
        narrative.put("type", "string");
        narrative.put("minLength", 1);
        narrative.put("maxLength", 1200);

        ObjectNode insights = properties.putObject("insights");
        insights.put("type", "array");
        insights.put("maxItems", MAX_INSIGHTS);
        ObjectNode item = insights.putObject("items");
        item.put("type", "object");
        item.put("additionalProperties", false);
        ArrayNode insightRequired = item.putArray("required");
        insightRequired.add("kind").add("text").add("confidence").add("sourceIds")
                .add("urgency").add("deadline").add("transactionType")
                .add("amount").add("currency").add("transactionDate");
        ObjectNode insightProperties = item.putObject("properties");

        ObjectNode kind = insightProperties.putObject("kind");
        kind.put("type", "string");
        kind.putArray("enum").add("URGENT_ACTION").add("AWAITING_REPLY")
                .add("REPLY_RECEIVED").add("TRANSACTION");
        ObjectNode text = insightProperties.putObject("text");
        text.put("type", "string");
        text.put("minLength", 1);
        text.put("maxLength", 600);
        ObjectNode confidence = insightProperties.putObject("confidence");
        confidence.put("type", "number");
        confidence.put("minimum", 0);
        confidence.put("maximum", 1);

        ObjectNode sourceIds = insightProperties.putObject("sourceIds");
        sourceIds.put("type", "array");
        sourceIds.put("minItems", 1);
        sourceIds.put("maxItems", 8);
        ObjectNode sourceId = sourceIds.putObject("items");
        sourceId.put("type", "string");
        sourceId.put("pattern", "^m[0-9]{1,4}$");

        nullableEnum(insightProperties, "urgency", "string", "HIGH", "MEDIUM", "LOW");
        nullableString(insightProperties, "deadline", 40);
        nullableEnum(insightProperties, "transactionType", "string", "RECEIPT", "INVOICE", "REFUND");
        ObjectNode amount = insightProperties.putObject("amount");
        amount.putArray("type").add("number").add("null");
        amount.put("minimum", 0);
        amount.put("maximum", 1000000000);
        nullableString(insightProperties, "currency", 3);
        nullableString(insightProperties, "transactionDate", 10);
        return root;
    }

    @Override
    public Output validate(JsonNode output) {
        requireExactFields(output, Set.of("narrative", "insights"));
        String narrative = boundedText(output.path("narrative"), 1200);
        if (hasLineBreak(narrative)) throw invalid();
        JsonNode insightsNode = output.path("insights");
        if (!insightsNode.isArray() || insightsNode.size() > MAX_INSIGHTS) throw invalid();
        List<Claim> claims = new ArrayList<>();
        for (JsonNode insight : insightsNode) claims.add(validateClaim(insight));
        return new Output(narrative, claims);
    }

    private Claim validateClaim(JsonNode value) {
        requireExactFields(value, Set.of(
                "kind", "text", "confidence", "sourceIds", "urgency", "deadline",
                "transactionType", "amount", "currency", "transactionDate"));
        String kind = enumText(value.path("kind"), Set.of(
                "URGENT_ACTION", "AWAITING_REPLY", "REPLY_RECEIVED", "TRANSACTION"));
        String text = boundedText(value.path("text"), 600);
        if (hasLineBreak(text)) throw invalid();
        JsonNode confidenceNode = value.path("confidence");
        if (!confidenceNode.isNumber()) throw invalid();
        double confidence = confidenceNode.asDouble();
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) throw invalid();

        JsonNode sourceIdsNode = value.path("sourceIds");
        if (!sourceIdsNode.isArray() || sourceIdsNode.isEmpty() || sourceIdsNode.size() > 8) throw invalid();
        Set<String> sourceIds = new HashSet<>();
        for (JsonNode sourceIdNode : sourceIdsNode) {
            String sourceId = boundedText(sourceIdNode, 8);
            if (!sourceId.matches("m[0-9]{1,4}")
                    || !allowedSourceTokens.contains(sourceId)
                    || !sourceIds.add(sourceId)) {
                throw invalid();
            }
        }

        String urgency = nullableEnumText(value.path("urgency"), Set.of("HIGH", "MEDIUM", "LOW"));
        String deadlineText = nullableText(value.path("deadline"), 40);
        Instant deadline = null;
        if (deadlineText != null) {
            try {
                deadline = Instant.parse(deadlineText);
            } catch (RuntimeException exception) {
                throw invalid();
            }
        }
        String transactionType = nullableEnumText(
                value.path("transactionType"), Set.of("RECEIPT", "INVOICE", "REFUND"));
        BigDecimal amount = null;
        JsonNode amountNode = value.path("amount");
        if (!amountNode.isNull()) {
            if (!amountNode.isNumber()) throw invalid();
            amount = amountNode.decimalValue();
            if (amount.signum() < 0 || amount.compareTo(new BigDecimal("1000000000")) > 0
                    || amount.scale() > 4) throw invalid();
        }
        String currency = nullableText(value.path("currency"), 3);
        if (currency != null && !currency.matches("[A-Z]{3}")) throw invalid();
        String transactionDateText = nullableText(value.path("transactionDate"), 10);
        LocalDate transactionDate = null;
        if (transactionDateText != null) {
            try {
                transactionDate = LocalDate.parse(transactionDateText);
            } catch (RuntimeException exception) {
                throw invalid();
            }
        }

        boolean transaction = "TRANSACTION".equals(kind);
        if (transaction != (transactionType != null)
                || (!transaction && (amount != null || currency != null || transactionDate != null))) {
            throw invalid();
        }
        if (!Set.of("URGENT_ACTION", "AWAITING_REPLY").contains(kind)
                && (urgency != null || deadline != null)) {
            throw invalid();
        }
        return new Claim(kind, text, confidence, List.copyOf(sourceIds), urgency, deadline,
                transactionType, amount, currency, transactionDate);
    }

    private static void nullableEnum(
            ObjectNode properties, String name, String type, String... values) {
        ObjectNode property = properties.putObject(name);
        property.putArray("type").add(type).add("null");
        ArrayNode allowed = property.putArray("enum");
        for (String value : values) allowed.add(value);
        allowed.addNull();
    }

    private static void nullableString(ObjectNode properties, String name, int maxLength) {
        ObjectNode property = properties.putObject(name);
        property.putArray("type").add("string").add("null");
        property.put("maxLength", maxLength);
    }

    private static String boundedText(JsonNode node, int maxLength) {
        if (!node.isTextual()) throw invalid();
        String text = node.textValue();
        if (text.isBlank() || text.length() > maxLength || text.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid();
        }
        return text.trim();
    }

    private static String nullableText(JsonNode node, int maxLength) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (!node.isTextual()) throw invalid();
        String text = node.textValue();
        if (text.length() > maxLength || text.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return text;
    }

    private static String enumText(JsonNode node, Set<String> values) {
        String text = boundedText(node, 64);
        if (!values.contains(text)) throw invalid();
        return text;
    }

    private static String nullableEnumText(JsonNode node, Set<String> values) {
        String text = nullableText(node, 64);
        if (text != null && !values.contains(text)) throw invalid();
        return text;
    }

    private static void requireExactFields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject() || node.size() != expected.size()) throw invalid();
        node.fieldNames().forEachRemaining(field -> {
            if (!expected.contains(field)) throw invalid();
        });
    }

    private static boolean hasLineBreak(String value) {
        return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
    }

    private static StructuredOutputValidationException invalid() {
        return new StructuredOutputValidationException();
    }

    record Output(String narrative, List<Claim> insights) {
        Output {
            insights = List.copyOf(insights);
        }
    }

    record Claim(
            String kind,
            String text,
            double confidence,
            List<String> sourceTokens,
            String urgency,
            Instant deadline,
            String transactionType,
            BigDecimal amount,
            String currency,
            LocalDate transactionDate) {
        Claim {
            sourceTokens = List.copyOf(sourceTokens);
        }
    }
}

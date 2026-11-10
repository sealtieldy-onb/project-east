package com.east.app.reports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.east.app.ai.StructuredOutputValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReportInsightContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void acceptsDateOnlyDeadlineAndStoresItAtStartOfUtcDay() {
        ReportInsightContract.Output output = contract().validate(output("2026-10-12"));

        assertEquals(Instant.parse("2026-10-12T00:00:00Z"), output.insights().getFirst().deadline());
    }

    @Test
    void keepsExplicitIsoInstantAndRejectsInvalidDateOnlyDeadline() {
        ReportInsightContract.Output timestamp = contract().validate(output("2026-10-12T09:30:00Z"));
        assertEquals(Instant.parse("2026-10-12T09:30:00Z"), timestamp.insights().getFirst().deadline());

        assertThrows(StructuredOutputValidationException.class,
                () -> contract().validate(output("2026-13-45")));
    }

    private static ReportInsightContract contract() {
        return new ReportInsightContract(Set.of("m1"));
    }

    private static ObjectNode output(String deadline) {
        ObjectNode output = MAPPER.createObjectNode();
        output.put("narrative", "A synthetic action is due.");
        ArrayNode insights = output.putArray("insights");
        ObjectNode insight = insights.addObject();
        insight.put("kind", "URGENT_ACTION");
        insight.put("text", "Review the synthetic agreement.");
        insight.put("confidence", 0.9);
        insight.putArray("sourceIds").add("m1");
        insight.put("urgency", "HIGH");
        insight.put("deadline", deadline);
        insight.putNull("transactionType");
        insight.putNull("amount");
        insight.putNull("currency");
        insight.putNull("transactionDate");
        return output;
    }
}

package com.east.app.reports;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class ReportPayloadAad {

    private ReportPayloadAad() { }

    static byte[] narrative(UUID appUserId, UUID reportId) {
        return ("east:summary-run:v1:user:" + appUserId + ":run:" + reportId)
                .getBytes(StandardCharsets.UTF_8);
    }

    static byte[] insight(UUID appUserId, UUID reportId, UUID insightId) {
        return ("east:assistant-insight:v1:user:" + appUserId + ":run:" + reportId + ":insight:" + insightId)
                .getBytes(StandardCharsets.UTF_8);
    }
}

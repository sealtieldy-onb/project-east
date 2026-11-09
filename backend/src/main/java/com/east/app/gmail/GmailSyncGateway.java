package com.east.app.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Narrow read-only Gmail API surface used by the sync worker and its isolated tests. */
interface GmailSyncGateway {
    JsonNode getProfile(String accessToken);

    JsonNode listMessages(
            String accessToken,
            String query,
            Integer maxResults,
            String pageToken,
            List<String> labelIds,
            Boolean includeSpamTrash);

    JsonNode getThread(String accessToken, String threadId, String format, List<String> metadataHeaders);

    JsonNode getThread(
            String accessToken,
            String threadId,
            String format,
            List<String> metadataHeaders,
            String fields);

    JsonNode getMessage(String accessToken, String messageId, String format, List<String> metadataHeaders);

    JsonNode getMessage(
            String accessToken,
            String messageId,
            String format,
            List<String> metadataHeaders,
            String fields);

    JsonNode listHistory(
            String accessToken,
            String startHistoryId,
            Integer maxResults,
            String pageToken,
            String labelId,
            List<String> historyTypes);

    JsonNode listLabels(String accessToken);
}

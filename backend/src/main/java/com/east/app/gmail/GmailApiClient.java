package com.east.app.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Small server-side client for Gmail REST API v1.
 *
 * <p>The caller supplies a mailbox-specific access token to each method. This client does not
 * persist credentials or response content, and it deliberately contains no retry behavior.
 */
@Component
public final class GmailApiClient implements GmailSyncGateway {

    private static final String GMAIL_API_BASE_URL = "https://gmail.googleapis.com/gmail/v1";
    private static final String USER_ID = "me";
    private static final int MAX_PAGE_SIZE = 500;
    private static final int MAX_BULK_MESSAGE_IDS = 1_000;
    private static final int MAX_JSON_RESPONSE_BYTES = 16 * 1024 * 1024;
    // Gmail returns URL-safe base64, which expands a 50 MiB decoded attachment to about 66.7 MiB.
    // Leave room for the surrounding JSON object while preserving the attachment endpoint's 50 MiB limit.
    private static final int MAX_ATTACHMENT_JSON_RESPONSE_BYTES = 68 * 1024 * 1024;
    private static final int MAX_ATTACHMENT_BYTES = 50 * 1024 * 1024;
    private static final int MIME_FIELD_MASK_DEPTH = 24;
    private static final int MAX_ERROR_RESPONSE_BYTES = 64 * 1024;
    private static final int RESPONSE_READ_BUFFER_BYTES = 8 * 1024;
    private static final Pattern SAFE_ERROR_CODE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    static final String MESSAGE_STRUCTURE_FIELDS = messageFields(false);
    static final String MESSAGE_BODY_FIELDS = messageFields(true);
    static final String MESSAGE_PART_DATA_FIELDS = partDataMessageFields();
    static final String THREAD_STRUCTURE_FIELDS = "id,messages(" + MESSAGE_STRUCTURE_FIELDS + ")";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public GmailApiClient(RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this.restClient = Objects.requireNonNull(restClientBuilder, "restClientBuilder")
                .baseUrl(GMAIL_API_BASE_URL)
                .build();
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public JsonNode getProfile(String accessToken) {
        return get(accessToken, null, "users", USER_ID, "profile");
    }

    public JsonNode listMessages(
            String accessToken,
            String query,
            Integer maxResults,
            String pageToken,
            List<String> labelIds,
            Boolean includeSpamTrash) {
        validatePageSize(maxResults);
        return get(accessToken, uri -> {
            addTextParam(uri, "q", query);
            addNumberParam(uri, "maxResults", maxResults);
            addTextParam(uri, "pageToken", pageToken);
            addRepeatedParams(uri, "labelIds", labelIds);
            if (includeSpamTrash != null) {
                uri.queryParam("includeSpamTrash", includeSpamTrash);
            }
        }, "users", USER_ID, "messages");
    }

    public JsonNode getMessage(String accessToken, String messageId, String format, List<String> metadataHeaders) {
        return getMessage(accessToken, messageId, format, metadataHeaders, null);
    }

    public JsonNode getMessage(
            String accessToken,
            String messageId,
            String format,
            List<String> metadataHeaders,
            String fields) {
        return getMessage(
                accessToken, messageId, format, metadataHeaders, fields, MAX_JSON_RESPONSE_BYTES);
    }

    private JsonNode getMessage(
            String accessToken,
            String messageId,
            String format,
            List<String> metadataHeaders,
            String fields,
            int maxResponseBytes) {
        requireText(messageId, "messageId");
        return jsonRequest(HttpMethod.GET, accessToken, null, uri -> {
            addTextParam(uri, "format", format);
            addRepeatedParams(uri, "metadataHeaders", metadataHeaders);
            addTextParam(uri, "fields", fields);
        }, maxResponseBytes, "users", USER_ID, "messages", messageId);
    }

    public JsonNode listThreads(
            String accessToken,
            String query,
            Integer maxResults,
            String pageToken,
            List<String> labelIds,
            Boolean includeSpamTrash) {
        validatePageSize(maxResults);
        return get(accessToken, uri -> {
            addTextParam(uri, "q", query);
            addNumberParam(uri, "maxResults", maxResults);
            addTextParam(uri, "pageToken", pageToken);
            addRepeatedParams(uri, "labelIds", labelIds);
            if (includeSpamTrash != null) {
                uri.queryParam("includeSpamTrash", includeSpamTrash);
            }
        }, "users", USER_ID, "threads");
    }

    public JsonNode listDrafts(
            String accessToken,
            String query,
            Integer maxResults,
            String pageToken,
            Boolean includeSpamTrash) {
        validatePageSize(maxResults);
        return get(accessToken, uri -> {
            addTextParam(uri, "q", query);
            addNumberParam(uri, "maxResults", maxResults);
            addTextParam(uri, "pageToken", pageToken);
            if (includeSpamTrash != null) {
                uri.queryParam("includeSpamTrash", includeSpamTrash);
            }
        }, "users", USER_ID, "drafts");
    }

    public JsonNode getThread(String accessToken, String threadId, String format, List<String> metadataHeaders) {
        return getThread(accessToken, threadId, format, metadataHeaders, null);
    }

    public JsonNode getThread(
            String accessToken,
            String threadId,
            String format,
            List<String> metadataHeaders,
            String fields) {
        requireText(threadId, "threadId");
        return get(accessToken, uri -> {
            addTextParam(uri, "format", format);
            addRepeatedParams(uri, "metadataHeaders", metadataHeaders);
            addTextParam(uri, "fields", fields);
        }, "users", USER_ID, "threads", threadId);
    }

    /**
     * Fetches one inline MIME part after an explicit user request. Gmail's fields selector projects
     * the same fields onto every array element, so sibling inline data can arrive in this response;
     * this method extracts only the requested part and removes all body.data nodes before returning.
     */
    public Optional<InlineAttachmentPayload> getInlineAttachment(
            String accessToken,
            String messageId,
            String partId) {
        requireText(partId, "partId");
        JsonNode message = getMessage(
                accessToken,
                messageId,
                "full",
                null,
                MESSAGE_PART_DATA_FIELDS,
                MAX_ATTACHMENT_JSON_RESPONSE_BYTES);
        InlineAttachmentAccumulator accumulator = new InlineAttachmentAccumulator(partId);
        clearAndExtractInlineData(message.path("payload"), accumulator);
        if (accumulator.tooLarge) {
            wipe(accumulator.bytes);
            throw new GmailResponseTooLargeException("The Gmail attachment exceeded East's local response limit.");
        }
        if (accumulator.invalid || accumulator.matchCount > 1) {
            wipe(accumulator.bytes);
            throw new IllegalStateException("Gmail returned invalid inline attachment data.");
        }
        if (accumulator.matchCount != 1 || accumulator.bytes == null) {
            wipe(accumulator.bytes);
            return Optional.empty();
        }
        InlineAttachmentPayload result = new InlineAttachmentPayload(
                accumulator.filename,
                accumulator.mimeType,
                accumulator.contentId,
                accumulator.declaredSize,
                accumulator.bytes);
        wipe(accumulator.bytes);
        return Optional.of(result);
    }

    public JsonNode getDraft(String accessToken, String draftId, String format) {
        requireText(draftId, "draftId");
        return get(accessToken, uri -> addTextParam(uri, "format", format),
                "users", USER_ID, "drafts", draftId);
    }

    public JsonNode listLabels(String accessToken) {
        return get(accessToken, null, "users", USER_ID, "labels");
    }

    public JsonNode createLabel(String accessToken, Map<String, Object> label) {
        return postJson(accessToken, label, null, "users", USER_ID, "labels");
    }

    public JsonNode updateLabel(String accessToken, String labelId, Map<String, Object> changes) {
        requireText(labelId, "labelId");
        return jsonRequest(HttpMethod.PATCH, accessToken, changes, null, "users", USER_ID, "labels", labelId);
    }

    public void deleteLabel(String accessToken, String labelId) {
        requireText(labelId, "labelId");
        noContentRequest(HttpMethod.DELETE, accessToken, null, "users", USER_ID, "labels", labelId);
    }

    public JsonNode modifyThreadLabels(
            String accessToken, String threadId, List<String> addLabelIds, List<String> removeLabelIds) {
        requireText(threadId, "threadId");
        Map<String, Object> body = modificationBody(addLabelIds, removeLabelIds);
        return postJson(accessToken, body, null, "users", USER_ID, "threads", threadId, "modify");
    }

    public JsonNode modifyMessageLabels(
            String accessToken, String messageId, List<String> addLabelIds, List<String> removeLabelIds) {
        requireText(messageId, "messageId");
        Map<String, Object> body = modificationBody(addLabelIds, removeLabelIds);
        return postJson(accessToken, body, null, "users", USER_ID, "messages", messageId, "modify");
    }

    public JsonNode trashThread(String accessToken, String threadId) {
        requireText(threadId, "threadId");
        return postJson(accessToken, Map.of(), null, "users", USER_ID, "threads", threadId, "trash");
    }

    public JsonNode untrashThread(String accessToken, String threadId) {
        requireText(threadId, "threadId");
        return postJson(accessToken, Map.of(), null, "users", USER_ID, "threads", threadId, "untrash");
    }

    public JsonNode trashMessage(String accessToken, String messageId) {
        requireText(messageId, "messageId");
        return postJson(accessToken, Map.of(), null, "users", USER_ID, "messages", messageId, "trash");
    }

    public JsonNode untrashMessage(String accessToken, String messageId) {
        requireText(messageId, "messageId");
        return postJson(accessToken, Map.of(), null, "users", USER_ID, "messages", messageId, "untrash");
    }

    public JsonNode bulkModifyMessages(
            String accessToken, List<String> messageIds, List<String> addLabelIds, List<String> removeLabelIds) {
        List<String> ids = requireItems(messageIds, "messageIds");
        if (ids.size() > MAX_BULK_MESSAGE_IDS) {
            throw new IllegalArgumentException("Gmail batch modify accepts at most 1000 message IDs.");
        }
        Map<String, Object> body = Map.of(
                "ids", ids,
                "addLabelIds", safeItems(addLabelIds),
                "removeLabelIds", safeItems(removeLabelIds));
        return postJson(accessToken, body, null, "users", USER_ID, "messages", "batchModify");
    }

    public JsonNode listHistory(
            String accessToken,
            String startHistoryId,
            Integer maxResults,
            String pageToken,
            String labelId,
            List<String> historyTypes) {
        requireText(startHistoryId, "startHistoryId");
        validatePageSize(maxResults);
        return get(accessToken, uri -> {
            uri.queryParam("startHistoryId", startHistoryId);
            addNumberParam(uri, "maxResults", maxResults);
            addTextParam(uri, "pageToken", pageToken);
            addTextParam(uri, "labelId", labelId);
            addRepeatedParams(uri, "historyTypes", historyTypes);
        }, "users", USER_ID, "history");
    }

    /** Returns attachment bytes decoded from Gmail's URL-safe base64 response. */
    public byte[] getAttachmentBytes(String accessToken, String messageId, String attachmentId) {
        requireText(messageId, "messageId");
        requireText(attachmentId, "attachmentId");
        JsonNode response = jsonRequest(
                HttpMethod.GET,
                accessToken,
                null,
                null,
                MAX_ATTACHMENT_JSON_RESPONSE_BYTES,
                "users",
                USER_ID,
                "messages",
                messageId,
                "attachments",
                attachmentId);
        JsonNode data = response.path("data");
        if (!data.isTextual()) {
            throw new IllegalStateException("Gmail returned an attachment response without data.");
        }
        String encoded = data.asText();
        if (encoded.length() > ((long) MAX_ATTACHMENT_BYTES + 2L) / 3L * 4L + 1024L * 1024L) {
            throw new GmailResponseTooLargeException("The Gmail attachment exceeded East's local response limit.");
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            if (decoded.length > MAX_ATTACHMENT_BYTES) {
                java.util.Arrays.fill(decoded, (byte) 0);
                throw new GmailResponseTooLargeException("The Gmail attachment exceeded East's local response limit.");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Gmail returned invalid attachment data.");
        }
    }

    /** Creates a Gmail draft from the supplied Gmail Message resource. */
    public JsonNode createDraft(String accessToken, Map<String, Object> message) {
        requireBody(message, "message");
        return postJson(accessToken, Map.of("message", message), null, "users", USER_ID, "drafts");
    }

    /** Replaces a Gmail draft using the supplied Gmail Message resource. */
    public JsonNode updateDraft(String accessToken, String draftId, Map<String, Object> message) {
        requireText(draftId, "draftId");
        requireBody(message, "message");
        Map<String, Object> body = Map.of("id", draftId, "message", message);
        return jsonRequest(HttpMethod.PUT, accessToken, body, null, "users", USER_ID, "drafts", draftId);
    }

    public void deleteDraft(String accessToken, String draftId) {
        requireText(draftId, "draftId");
        noContentRequest(HttpMethod.DELETE, accessToken, null, "users", USER_ID, "drafts", draftId);
    }

    /** Sends a Gmail Message resource supplied by an explicit caller action. */
    public JsonNode sendMessage(String accessToken, Map<String, Object> message) {
        requireBody(message, "message");
        return postJson(accessToken, message, null, "users", USER_ID, "messages", "send");
    }

    /** Sends a Gmail draft with the reviewed replacement message in the same request. */
    public JsonNode sendDraft(String accessToken, String draftId, Map<String, Object> message) {
        requireText(draftId, "draftId");
        requireBody(message, "message");
        return postJson(accessToken, draftSendBody(draftId, message), null, "users", USER_ID, "drafts", "send");
    }

    static Map<String, Object> draftSendBody(String draftId, Map<String, Object> message) {
        requireText(draftId, "draftId");
        requireBody(message, "message");
        return Map.of("id", draftId, "message", message);
    }

    private JsonNode get(String accessToken, Consumer<UriBuilder> query, String... pathSegments) {
        return jsonRequest(HttpMethod.GET, accessToken, null, query, pathSegments);
    }

    private JsonNode postJson(
            String accessToken, Object body, Consumer<UriBuilder> query, String... pathSegments) {
        return jsonRequest(HttpMethod.POST, accessToken, body, query, pathSegments);
    }

    private JsonNode jsonRequest(
            HttpMethod method,
            String accessToken,
            Object body,
            Consumer<UriBuilder> query,
            String... pathSegments) {
        return jsonRequest(method, accessToken, body, query, MAX_JSON_RESPONSE_BYTES, pathSegments);
    }

    private JsonNode jsonRequest(
            HttpMethod method,
            String accessToken,
            Object body,
            Consumer<UriBuilder> query,
            int maxResponseBytes,
            String... pathSegments) {
        RestClient.RequestBodySpec request = request(method, accessToken, query, pathSegments);
        if (body != null) {
            request.body(body);
        }
        return request.exchange((httpRequest, response) -> {
            int status = response.getStatusCode().value();
            if (response.getStatusCode().isError()) {
                String errorCode;
                try {
                    errorCode = extractGoogleErrorCode(readBounded(response, MAX_ERROR_RESPONSE_BYTES));
                } catch (GmailResponseTooLargeException exception) {
                    errorCode = "UNKNOWN";
                }
                throw new GmailApiException(status, errorCode);
            }
            return parseJsonResponse(readBounded(response, maxResponseBytes));
        });
    }

    private void noContentRequest(
            HttpMethod method,
            String accessToken,
            Consumer<UriBuilder> query,
            String... pathSegments) {
        request(method, accessToken, query, pathSegments).retrieve()
                .onStatus(HttpStatusCode::isError, this::throwSanitizedError)
                .toBodilessEntity();
    }

    private RestClient.RequestBodySpec request(
            HttpMethod method,
            String accessToken,
            Consumer<UriBuilder> query,
            String... pathSegments) {
        String token = requireText(accessToken, "accessToken");
        return restClient.method(method)
                .uri(uriBuilder -> {
                    UriBuilder builder = uriBuilder.pathSegment(pathSegments);
                    if (query != null) {
                        query.accept(builder);
                    }
                    return builder.build();
                })
                .headers(headers -> setHeaders(headers, token));
    }

    private static void setHeaders(HttpHeaders headers, String accessToken) {
        headers.setBearerAuth(accessToken);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
    }

    private void throwSanitizedError(org.springframework.http.HttpRequest request, ClientHttpResponse response)
            throws IOException {
        int status = response.getStatusCode().value();
        String errorCode;
        try {
            errorCode = extractGoogleErrorCode(readBounded(response, MAX_ERROR_RESPONSE_BYTES));
        } catch (GmailResponseTooLargeException exception) {
            errorCode = "UNKNOWN";
        }
        throw new GmailApiException(status, errorCode);
    }

    private String extractGoogleErrorCode(byte[] responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode error = root == null ? null : root.path("error");
            if (error == null || error.isMissingNode() || error.isNull()) {
                return "UNKNOWN";
            }

            JsonNode errors = error.path("errors");
            if (errors.isArray() && !errors.isEmpty()) {
                String reason = safeErrorCode(errors.get(0).path("reason").asText(null));
                if (reason != null) {
                    return reason;
                }
            }

            String status = safeErrorCode(error.path("status").asText(null));
            if (status != null) {
                return status;
            }

            JsonNode code = error.path("code");
            if (code.isNumber()) {
                return Integer.toString(code.asInt());
            }
            return "UNKNOWN";
        } catch (IOException | RuntimeException ignored) {
            return "UNKNOWN";
        } finally {
            java.util.Arrays.fill(responseBody, (byte) 0);
        }
    }

    private JsonNode parseJsonResponse(byte[] responseBody) {
        try {
            return objectMapper.readTree(responseBody);
        } catch (IOException exception) {
            throw new IllegalStateException("Gmail returned an invalid JSON response.");
        } finally {
            java.util.Arrays.fill(responseBody, (byte) 0);
        }
    }

    private static byte[] readBounded(ClientHttpResponse response, int maximumBytes) {
        long contentLength = response.getHeaders().getContentLength();
        if (contentLength > maximumBytes) {
            throw new GmailResponseTooLargeException("Gmail returned a response larger than East's local limit.");
        }

        try (InputStream input = response.getBody(); ZeroingByteArrayOutputStream output = new ZeroingByteArrayOutputStream()) {
            byte[] buffer = new byte[RESPONSE_READ_BUFFER_BYTES];
            try {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (output.size() + read > maximumBytes) {
                        throw new GmailResponseTooLargeException(
                                "Gmail returned a response larger than East's local limit.");
                    }
                    output.write(buffer, 0, read);
                }
                return output.copy();
            } finally {
                java.util.Arrays.fill(buffer, (byte) 0);
                output.wipe();
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Gmail response could not be read safely.");
        }
    }

    private static final class ZeroingByteArrayOutputStream extends ByteArrayOutputStream {
        private byte[] copy() {
            return toByteArray();
        }

        private void wipe() {
            java.util.Arrays.fill(buf, (byte) 0);
            reset();
        }
    }

    private static String safeErrorCode(String value) {
        if (value == null || !SAFE_ERROR_CODE.matcher(value).matches()) {
            return null;
        }
        return value;
    }

    private static Map<String, Object> modificationBody(List<String> addLabelIds, List<String> removeLabelIds) {
        List<String> add = safeItems(addLabelIds);
        List<String> remove = safeItems(removeLabelIds);
        if (add.isEmpty() && remove.isEmpty()) {
            throw new IllegalArgumentException("At least one label must be added or removed.");
        }
        return Map.of("addLabelIds", add, "removeLabelIds", remove);
    }

    private static void addTextParam(UriBuilder uri, String name, String value) {
        if (value != null && !value.isBlank()) {
            uri.queryParam(name, value);
        }
    }

    private static String messageFields(boolean includeData) {
        String bodyFields = "attachmentId,size" + (includeData ? ",data" : "");
        String childFields = mimePartFields(includeData, MIME_FIELD_MASK_DEPTH - 1);
        return "id,threadId,labelIds,internalDate,snippet,payload(partId,mimeType,filename,headers,body("
                + bodyFields + "),parts(" + childFields + "))";
    }

    private static String partDataMessageFields() {
        String rootBody = "attachmentId,size,data";
        String childFields = mimePartFields(true, MIME_FIELD_MASK_DEPTH - 1);
        return "id,payload(partId,mimeType,filename,headers,body(" + rootBody + "),parts(" + childFields + "))";
    }

    private static String mimePartFields(boolean includeData, int remainingDepth) {
        String bodyFields = "attachmentId,size" + (includeData ? ",data" : "");
        String current = "partId,mimeType,filename,headers,body(" + bodyFields + ")";
        if (remainingDepth <= 0) return current;
        return current + ",parts(" + mimePartFields(includeData, remainingDepth - 1) + ")";
    }

    private static void clearAndExtractInlineData(JsonNode part, InlineAttachmentAccumulator accumulator) {
        if (part == null || !part.isObject()) return;
        String partId = part.path("partId").asText("");
        String filename = part.path("filename").asText("");
        String mimeType = part.path("mimeType").asText("");
        String contentId = contentId(part);
        JsonNode body = part.path("body");
        JsonNode data = null;
        boolean target = accumulator.partId.equals(partId);
        boolean hasExternalId = body.path("attachmentId").isTextual()
                && !body.path("attachmentId").asText().isBlank();
        Long declaredSize = body.path("size").isNumber() && body.path("size").asLong() >= 0
                ? body.path("size").asLong()
                : null;
        if (body instanceof ObjectNode bodyObject) {
            data = bodyObject.remove("data");
        }
        if (target) {
            accumulator.matchCount++;
            if (accumulator.matchCount == 1 && !hasExternalId
                    && (!filename.isBlank() || contentId != null)
                    && data != null && data.isTextual()) {
                String encoded = data.asText();
                long maxEncodedLength = ((long) MAX_ATTACHMENT_BYTES + 2L) / 3L * 4L + 1024L;
                if (encoded.length() > maxEncodedLength) {
                    accumulator.tooLarge = true;
                } else {
                    try {
                        byte[] decoded = Base64.getUrlDecoder().decode(encoded);
                        if (decoded.length > MAX_ATTACHMENT_BYTES) {
                            wipe(decoded);
                            accumulator.tooLarge = true;
                        } else {
                            accumulator.bytes = decoded;
                            accumulator.filename = filename.isBlank() ? "attachment" : filename;
                            accumulator.mimeType = mimeType;
                            accumulator.contentId = contentId;
                            accumulator.declaredSize = declaredSize;
                        }
                    } catch (IllegalArgumentException exception) {
                        accumulator.invalid = true;
                    }
                }
            }
        }
        JsonNode children = part.path("parts");
        if (children.isArray()) {
            for (JsonNode child : children) clearAndExtractInlineData(child, accumulator);
        }
    }

    private static String contentId(JsonNode part) {
        JsonNode headers = part.path("headers");
        if (!headers.isArray()) return null;
        for (JsonNode header : headers) {
            if ("content-id".equalsIgnoreCase(header.path("name").asText(""))) {
                return GmailContentId.fromHeader(header.path("value").asText(null));
            }
        }
        return null;
    }

    private static void wipe(byte[] value) {
        if (value != null) Arrays.fill(value, (byte) 0);
    }

    private static final class InlineAttachmentAccumulator {
        private final String partId;
        private int matchCount;
        private boolean tooLarge;
        private boolean invalid;
        private String filename;
        private String mimeType;
        private String contentId;
        private Long declaredSize;
        private byte[] bytes;

        private InlineAttachmentAccumulator(String partId) {
            this.partId = partId;
        }
    }

    public record InlineAttachmentPayload(
            String filename, String mimeType, String contentId, Long declaredSize, byte[] bytes) {
        public InlineAttachmentPayload {
            bytes = bytes == null ? null : Arrays.copyOf(bytes, bytes.length);
        }

        @Override
        public byte[] bytes() {
            return bytes == null ? null : Arrays.copyOf(bytes, bytes.length);
        }

        public void destroy() {
            wipe(bytes);
        }

        @Override
        public String toString() {
            return "InlineAttachmentPayload[metadata=REDACTED, bytes=REDACTED]";
        }
    }

    private static void addNumberParam(UriBuilder uri, String name, Integer value) {
        if (value != null) {
            uri.queryParam(name, value);
        }
    }

    private static void addRepeatedParams(UriBuilder uri, String name, List<String> values) {
        for (String value : safeItems(values)) {
            uri.queryParam(name, value);
        }
    }

    private static List<String> safeItems(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(Objects::nonNull).filter(value -> !value.isBlank()).toList();
    }

    private static List<String> requireItems(List<String> values, String name) {
        List<String> result = safeItems(values);
        if (result.isEmpty()) {
            throw new IllegalArgumentException(name + " must contain at least one value.");
        }
        return result;
    }

    private static void validatePageSize(Integer maxResults) {
        if (maxResults != null && (maxResults < 1 || maxResults > MAX_PAGE_SIZE)) {
            throw new IllegalArgumentException("maxResults must be between 1 and 500.");
        }
    }

    private static void requireBody(Map<String, Object> body, String name) {
        if (body == null || body.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty.");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }

    /** A sanitized Gmail HTTP failure; contains no response message, URL, token, or payload. */
    public static final class GmailApiException extends RuntimeException {

        private final int httpStatus;
        private final String googleErrorCode;
        private boolean mailboxReauthorizationRequired;

        GmailApiException(int httpStatus, String googleErrorCode) {
            super("Gmail API request failed (HTTP " + httpStatus + ", Google error " + googleErrorCode + ").");
            this.httpStatus = httpStatus;
            this.googleErrorCode = googleErrorCode;
        }

        public int getHttpStatus() {
            return httpStatus;
        }

        public String getGoogleErrorCode() {
            return googleErrorCode;
        }

        void setMailboxReauthorizationRequired(boolean required) {
            mailboxReauthorizationRequired = required;
        }

        public boolean isMailboxReauthorizationRequired() {
            return mailboxReauthorizationRequired;
        }
    }

    /** The response exceeded a bounded local parser buffer; contains no response content. */
    public static final class GmailResponseTooLargeException extends RuntimeException {
        public GmailResponseTooLargeException(String message) {
            super(message);
        }
    }
}

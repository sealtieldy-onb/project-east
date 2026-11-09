package com.east.app.mailactions;

import java.util.UUID;

public record ThreadActionOutcome(
        UUID threadId,
        boolean success,
        String errorCode,
        Integer gmailHttpStatus,
        String googleErrorCode) {

    static ThreadActionOutcome success(UUID threadId) {
        return new ThreadActionOutcome(threadId, true, null, null, null);
    }

    static ThreadActionOutcome failure(UUID threadId, String errorCode) {
        return new ThreadActionOutcome(threadId, false, errorCode, null, null);
    }

    static ThreadActionOutcome gmailFailure(UUID threadId, int httpStatus, String googleErrorCode) {
        return new ThreadActionOutcome(threadId, false, "GMAIL_API_ERROR", httpStatus, googleErrorCode);
    }
}

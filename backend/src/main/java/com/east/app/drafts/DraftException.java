package com.east.app.drafts;

import org.springframework.http.HttpStatus;

final class DraftException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    DraftException(HttpStatus status, String message) {
        this(status, message, null);
    }

    private DraftException(HttpStatus status, String message, String errorCode) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    HttpStatus status() {
        return status;
    }

    String errorCode() {
        return errorCode;
    }

    static DraftException badRequest(String message) {
        return new DraftException(HttpStatus.BAD_REQUEST, message);
    }

    static DraftException notFound() {
        return new DraftException(HttpStatus.NOT_FOUND, "The requested draft or attachment was not found.");
    }

    static DraftException conflict(String message) {
        return new DraftException(HttpStatus.CONFLICT, message);
    }

    static DraftException reconciliationRequired(String message) {
        return new DraftException(HttpStatus.CONFLICT, message, "DRAFT_RECONCILIATION_REQUIRED");
    }

    static DraftException upstreamFailure() {
        return new DraftException(HttpStatus.BAD_GATEWAY,
                "Gmail did not confirm the operation. Check Gmail before trying again; East did not automatically retry.");
    }

    static DraftException unauthorized() {
        return new DraftException(HttpStatus.UNAUTHORIZED, "Sign in with Google to continue.");
    }
}

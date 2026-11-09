package com.east.app.attachments;

import org.springframework.http.HttpStatus;

final class AttachmentContentException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    AttachmentContentException(HttpStatus status, String errorCode, String message) {
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
}

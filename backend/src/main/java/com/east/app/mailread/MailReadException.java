package com.east.app.mailread;

import org.springframework.http.HttpStatus;

final class MailReadException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    MailReadException(HttpStatus status, String errorCode, String message) {
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

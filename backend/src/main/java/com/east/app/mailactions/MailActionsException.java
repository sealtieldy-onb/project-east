package com.east.app.mailactions;

import org.springframework.http.HttpStatus;

public final class MailActionsException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public MailActionsException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus status() {
        return status;
    }

    public String errorCode() {
        return errorCode;
    }
}

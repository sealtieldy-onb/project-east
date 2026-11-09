package com.east.app.auth;

import org.springframework.http.HttpStatus;

final class AuthNotFoundException extends AuthFlowException {
    AuthNotFoundException() {
        super(HttpStatus.NOT_FOUND, "The requested local account or mailbox was not found.");
    }
}

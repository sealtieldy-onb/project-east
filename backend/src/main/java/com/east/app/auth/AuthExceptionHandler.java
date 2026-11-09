package com.east.app.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuthExceptionHandler {

    @ExceptionHandler(AuthFlowException.class)
    ResponseEntity<ProblemDetail> handleAuthFlow(AuthFlowException exception) {
        HttpStatus status = exception.status();
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, exception.getMessage()));
    }

    @ExceptionHandler(AuthRepository.DifferentGoogleIdentityException.class)
    ResponseEntity<ProblemDetail> handleDifferentIdentity() {
        HttpStatus status = HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(
                status,
                "This local installation is already linked to a different Google sign-in identity. Reset local data to change it."));
    }
}

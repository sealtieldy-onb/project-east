package com.east.app.vault;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class VaultExceptionHandler {

    @ExceptionHandler(VaultService.VaultRequestException.class)
    ResponseEntity<ProblemDetail> handleRequest(VaultService.VaultRequestException exception) {
        HttpStatus status = HttpStatus.valueOf(exception.statusCode());
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, exception.getMessage()));
    }

    @ExceptionHandler(VaultService.InvalidPassphraseException.class)
    ResponseEntity<ProblemDetail> handleInvalidPassphrase() {
        HttpStatus status = HttpStatus.UNAUTHORIZED;
        return ResponseEntity.status(status).body(
                ProblemDetail.forStatusAndDetail(status, "The current passphrase could not migrate existing encrypted data."));
    }

    @ExceptionHandler(VaultService.VaultLockedException.class)
    ResponseEntity<ProblemDetail> handleLocked() {
        HttpStatus status = HttpStatus.LOCKED;
        return ResponseEntity.status(status).body(
                ProblemDetail.forStatusAndDetail(status, "Complete the one-time local key migration or restore the local key file."));
    }
}

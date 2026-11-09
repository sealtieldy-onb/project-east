package com.east.app.settings;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class SettingsExceptionHandler {

    @ExceptionHandler(SettingsService.SettingsRequestException.class)
    ResponseEntity<Map<String, String>> handleSettingsRequest(SettingsService.SettingsRequestException exception) {
        return ResponseEntity.status(exception.statusCode()).body(Map.of("message", exception.getMessage()));
    }
}

package com.omnivid.api.common;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> handleApiException(ApiException exception) {
        return ResponseEntity.status(exception.status()).body(error(exception));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("Invalid request");
        return ResponseEntity.badRequest().body(error(message));
    }

    private Map<String, Object> error(String message) {
        return Map.of(
                "timestamp", Instant.now().toString(),
                "message", message
        );
    }

    private Map<String, Object> error(ApiException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("message", exception.getMessage());
        if (hasText(exception.suggestion())) {
            body.put("suggestion", exception.suggestion());
        }
        if (hasText(exception.detail())) {
            body.put("detail", exception.detail());
        }
        return body;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

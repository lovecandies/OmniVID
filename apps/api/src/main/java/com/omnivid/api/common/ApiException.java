package com.omnivid.api.common;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String suggestion;
    private final String detail;

    public ApiException(HttpStatus status, String message) {
        this(status, message, null, null);
    }

    public ApiException(HttpStatus status, String message, String suggestion, String detail) {
        super(message);
        this.status = status;
        this.suggestion = suggestion;
        this.detail = detail;
    }

    public HttpStatus status() {
        return status;
    }

    public String suggestion() {
        return suggestion;
    }

    public String detail() {
        return detail;
    }
}

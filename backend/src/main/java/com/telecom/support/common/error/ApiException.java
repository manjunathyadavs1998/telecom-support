package com.telecom.support.common.error;

import org.springframework.http.HttpStatus;

/** Base type for expected, client-facing errors. Mapped to RFC 7807 ProblemDetail by {@link GlobalExceptionHandler}. */
public abstract class ApiException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    protected ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }
}

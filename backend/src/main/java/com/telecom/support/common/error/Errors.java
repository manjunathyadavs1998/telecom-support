package com.telecom.support.common.error;

import org.springframework.http.HttpStatus;

/** Concrete client-facing exceptions. Kept together so the error vocabulary is visible in one place. */
public final class Errors {

    private Errors() {}

    public static final class NotFoundException extends ApiException {
        public NotFoundException(String what) {
            super(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " not found");
        }
    }

    public static final class ForbiddenException extends ApiException {
        public ForbiddenException(String message) {
            super(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
        }
    }

    public static final class ConflictException extends ApiException {
        public ConflictException(String code, String message) {
            super(HttpStatus.CONFLICT, code, message);
        }
    }

    /** A request that is well-formed but violates a business rule (e.g. invalid state transition). */
    public static final class BusinessRuleException extends ApiException {
        public BusinessRuleException(String code, String message) {
            super(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
        }
    }

    public static final class BadRequestException extends ApiException {
        public BadRequestException(String code, String message) {
            super(HttpStatus.BAD_REQUEST, code, message);
        }
    }

    public static final class TooManyRequestsException extends ApiException {
        public TooManyRequestsException(String message) {
            super(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", message);
        }
    }

    /** An integration (payment, provisioning, notification) is unavailable — NFR-AVAIL graceful degradation. */
    public static final class DependencyUnavailableException extends ApiException {
        public DependencyUnavailableException(String dependency) {
            super(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", dependency + " is temporarily unavailable");
        }
    }
}

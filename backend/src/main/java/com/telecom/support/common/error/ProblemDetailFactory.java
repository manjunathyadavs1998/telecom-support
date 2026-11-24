package com.telecom.support.common.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.support.common.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/**
 * Builds every RFC 7807 error body (00-overview §4). Shared by {@link GlobalExceptionHandler}, the JSON
 * {@code AuthenticationEntryPoint} and the {@code AccessDeniedHandler} (01 backend §4.4), so all errors look the same
 * and carry {@code correlationId}. {@code detail} is always user-safe text from {@link Problem}.
 */
@Component
public class ProblemDetailFactory {

    public static final String TYPE_BASE = "https://telecom.example/problems/";

    /** The 00-overview §4 error table. */
    public enum Problem {
        VALIDATION(HttpStatus.BAD_REQUEST, "validation", "Validation failed", "One or more fields are invalid."),
        BAD_REQUEST(HttpStatus.BAD_REQUEST, "bad-request", "Bad request", "The request could not be read."),
        UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "unauthenticated", "Unauthenticated", "Please sign in to continue."),
        FORBIDDEN(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "You do not have permission to perform this action."),
        NOT_FOUND(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found."),
        METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "method-not-allowed", "Method not allowed",
                "This HTTP method is not supported on this path."),
        CONFLICT(HttpStatus.CONFLICT, "conflict", "Conflict", "The request conflicts with the current state."),
        PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "payload-too-large", "Payload too large",
                "The request body is too large."),
        UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-media-type", "Unsupported media type",
                "The request body must be application/json."),
        BUSINESS_RULE(HttpStatus.UNPROCESSABLE_ENTITY, "business-rule", "Business rule violated",
                "The request was rejected by a business rule."),
        IDEMPOTENCY_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, "idempotency-mismatch", "Idempotency key mismatch",
                "The Idempotency-Key was already used with a different request."),
        TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "too-many-requests", "Too many requests",
                "Too many requests. Please try again later."),
        INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "Internal error",
                "Something went wrong. Quote the correlation id when contacting support."),
        DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "dependency-unavailable", "Service unavailable",
                "A dependent service is temporarily unavailable.");

        private final HttpStatus status;
        private final String type;
        private final String title;
        private final String detail;

        Problem(HttpStatus status, String type, String title, String detail) {
            this.status = status;
            this.type = type;
            this.title = title;
            this.detail = detail;
        }

        public HttpStatus status() {
            return status;
        }

        public String detail() {
            return detail;
        }

        /** The table row for a framework-raised status; other 4xx fall back to bad-request, other 5xx to internal. */
        public static Problem forStatus(HttpStatusCode status) {
            return switch (status.value()) {
                case 400 -> BAD_REQUEST;
                case 401 -> UNAUTHENTICATED;
                case 403 -> FORBIDDEN;
                case 404 -> NOT_FOUND;
                case 405 -> METHOD_NOT_ALLOWED;
                case 409 -> CONFLICT;
                case 413 -> PAYLOAD_TOO_LARGE;
                case 415 -> UNSUPPORTED_MEDIA_TYPE;
                case 429 -> TOO_MANY_REQUESTS;
                case 503 -> DEPENDENCY_UNAVAILABLE;
                default -> status.is4xxClientError() ? BAD_REQUEST : INTERNAL;
            };
        }
    }

    /** One entry of {@code errors[]} on a validation problem. */
    public record FieldViolation(String field, String message) {}

    private final ObjectMapper objectMapper;

    public ProblemDetailFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ProblemDetail create(Problem problem, String instance) {
        return create(problem, problem.status(), instance);
    }

    /** {@code status} differs from the row's own status only for framework fallbacks (e.g. 406 as bad-request). */
    public ProblemDetail create(Problem problem, HttpStatusCode status, String instance) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, problem.detail);
        pd.setType(URI.create(TYPE_BASE + problem.type));
        pd.setTitle(problem.title);
        if (instance != null) {
            try {
                pd.setInstance(URI.create(instance));
            } catch (IllegalArgumentException ignored) {
                // a raw path that is not a valid URI: leave instance out rather than fail the error response
            }
        }
        pd.setProperty("correlationId", CorrelationIdFilter.current());
        return pd;
    }

    public ProblemDetail validation(List<FieldViolation> errors, String instance) {
        ProblemDetail pd = create(Problem.VALIDATION, instance);
        pd.setProperty("errors", errors);
        return pd;
    }

    /** Writes a problem straight to the servlet response (used by the security chain's handlers). */
    public void write(HttpServletRequest request, HttpServletResponse response, Problem problem) throws IOException {
        response.setStatus(problem.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), create(problem, request.getRequestURI()));
    }
}

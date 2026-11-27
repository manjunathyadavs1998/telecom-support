package com.telecom.support.common.error;

import com.telecom.support.common.error.ProblemDetailFactory.Problem;
import java.util.Optional;
import org.springframework.security.access.AccessDeniedException;

/**
 * Exceptions that services throw for the non-framework rows of the 00-overview §4 table. {@link GlobalExceptionHandler}
 * maps each to its {@link Problem}; the response {@code detail} is the row's user-safe text, or the exception's optional
 * user-safe detail when it carries one, never the exception message (which is for logs only).
 */
public final class ApiExceptions {

    private ApiExceptions() {
    }

    /**
     * Base type: carries the 00-overview §4 row it maps to. The message is for logs only; the optional user-safe detail
     * replaces the row's generic {@code detail} in the response.
     */
    public abstract static class ApiException extends RuntimeException {

        private final transient Problem problem;
        private final String userDetail;

        protected ApiException(Problem problem, String message) {
            this(problem, message, null);
        }

        protected ApiException(Problem problem, String message, String userDetail) {
            super(message);
            this.problem = problem;
            this.userDetail = userDetail;
        }

        public Problem problem() {
            return problem;
        }

        /** User-safe text for the response {@code detail}; empty = the row's generic text. */
        public Optional<String> userDetail() {
            return Optional.ofNullable(userDetail).filter(d -> !d.isBlank());
        }
    }

    /** 400 {@code bad-request}: a malformed request part that bean validation does not cover (e.g. a header). */
    public static class BadRequestException extends ApiException {
        public BadRequestException(String message, String userDetail) {
            super(Problem.BAD_REQUEST, message, userDetail);
        }
    }

    /** 404 {@code not-found}: the resource does not exist or is outside the caller's scope (never says which). */
    public static class NotFoundException extends ApiException {
        public NotFoundException(String message) {
            super(Problem.NOT_FOUND, message);
        }
    }

    /** 409 {@code conflict}: concurrency or state conflict (e.g. the idempotency key is still in progress). */
    public static class ConflictException extends ApiException {
        public ConflictException(String message) {
            super(Problem.CONFLICT, message);
        }
    }

    /**
     * 422 {@code business-rule}: a valid request rejected by a business rule. {@code userDetail}, when given, tells the
     * user why (e.g. "This account is not eligible for reactivation."); it must not contain internals.
     */
    public static class BusinessRuleException extends ApiException {
        public BusinessRuleException(String message) {
            super(Problem.BUSINESS_RULE, message);
        }

        public BusinessRuleException(String message, String userDetail) {
            super(Problem.BUSINESS_RULE, message, userDetail);
        }
    }

    /** 429 {@code too-many-requests} with a {@code Retry-After} header of {@code retryAfterSeconds} (at least 1). */
    public static class TooManyRequestsException extends ApiException {
        private final long retryAfterSeconds;

        public TooManyRequestsException(String message, long retryAfterSeconds) {
            super(Problem.TOO_MANY_REQUESTS, message);
            this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    /** 503 {@code dependency-unavailable}: an external port (sender, gateway, ...) failed or is unreachable. */
    public static class DependencyUnavailableException extends ApiException {
        public DependencyUnavailableException(String message) {
            super(Problem.DEPENDENCY_UNAVAILABLE, message);
        }
    }

    /** 422 {@code idempotency-mismatch}: {@code Idempotency-Key} reused with a different request body (00-overview §7). */
    public static class IdempotencyMismatchException extends ApiException {
        public IdempotencyMismatchException(String message) {
            super(Problem.IDEMPOTENCY_MISMATCH, message);
        }
    }

    /**
     * 403 {@code verification-required} (00-overview §4 rev 4, 03 backend R5): the inbound agent has no valid identity
     * check for the customer in the path. An {@link AccessDeniedException}, so the denial is recorded like any 403
     * (reason {@code verification_required}); {@link GlobalExceptionHandler} maps it to its own problem type.
     */
    public static class VerificationRequiredException extends AccessDeniedException {
        public VerificationRequiredException(String message) {
            super(message);
        }
    }
}

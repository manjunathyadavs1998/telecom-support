package com.telecom.support.common.error;

import com.telecom.support.common.error.ProblemDetailFactory.Problem;

/**
 * Exceptions that services throw for the non-framework rows of the 00-overview §4 table. {@link GlobalExceptionHandler}
 * maps each to its {@link Problem}; the response {@code detail} is the row's user-safe text, never the exception message.
 */
public final class ApiExceptions {

    private ApiExceptions() {
    }

    /** Base type: carries the 00-overview §4 row it maps to. The message is for logs only. */
    public abstract static class ApiException extends RuntimeException {

        private final transient Problem problem;

        protected ApiException(Problem problem, String message) {
            super(message);
            this.problem = problem;
        }

        public Problem problem() {
            return problem;
        }
    }

    /** 409 {@code conflict}: concurrency or state conflict (e.g. the idempotency key is still in progress). */
    public static class ConflictException extends ApiException {
        public ConflictException(String message) {
            super(Problem.CONFLICT, message);
        }
    }

    /** 422 {@code business-rule}: a valid request rejected by a business rule. */
    public static class BusinessRuleException extends ApiException {
        public BusinessRuleException(String message) {
            super(Problem.BUSINESS_RULE, message);
        }
    }

    /** 422 {@code idempotency-mismatch}: {@code Idempotency-Key} reused with a different request body (00-overview §7). */
    public static class IdempotencyMismatchException extends ApiException {
        public IdempotencyMismatchException(String message) {
            super(Problem.IDEMPOTENCY_MISMATCH, message);
        }
    }
}

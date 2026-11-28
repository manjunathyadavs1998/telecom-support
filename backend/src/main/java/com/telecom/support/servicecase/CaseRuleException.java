package com.telecom.support.servicecase;

import com.telecom.support.common.error.ApiExceptions.ApiException;
import com.telecom.support.common.error.ProblemDetailFactory.Problem;

/**
 * A case rule or concurrency conflict (design 04-outbound backend §5: 409 {@code conflict} / 422 {@code business-rule}
 * with {@code CaseProblem.reason}). The outbound controllers render {@link #reason()}; the message is for logs only.
 */
public class CaseRuleException extends ApiException {

    /** {@code CaseProblem.reason} values (openapi.yaml) and the status each maps to. */
    public enum Reason {
        ALREADY_ASSIGNED(Problem.CONFLICT, "This case was just claimed by someone else."),
        STALE_VERSION(Problem.CONFLICT, "This case was changed by someone else. Reload it and try again."),
        CASE_CLOSED(Problem.BUSINESS_RULE, "This case is resolved or closed."),
        UNASSIGNED(Problem.BUSINESS_RULE, "Claim or assign this case first."),
        INVALID_TRANSITION(Problem.BUSINESS_RULE, "This status change is not allowed from the current status."),
        INVALID_ASSIGNEE(Problem.BUSINESS_RULE, "This person can't be assigned outbound cases."),
        NO_CHANGE(Problem.BUSINESS_RULE, "The case is already assigned to this person."),
        CLOSURE_CONDITION_UNMET(Problem.BUSINESS_RULE, "The verification window has not elapsed yet."),
        REOPEN_WINDOW_EXPIRED(Problem.BUSINESS_RULE, "This case was closed too long ago to reopen."),
        BOOKMARK_LIMIT(Problem.BUSINESS_RULE, "You have reached the bookmark limit.");

        private final Problem problem;
        private final String userDetail;

        Reason(Problem problem, String userDetail) {
            this.problem = problem;
            this.userDetail = userDetail;
        }

        /** The wire value, e.g. {@code already_assigned}. */
        public String value() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private final transient Reason reason;

    CaseRuleException(Reason reason, String message) {
        super(reason.problem, message + ": " + reason.value(), reason.userDetail);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

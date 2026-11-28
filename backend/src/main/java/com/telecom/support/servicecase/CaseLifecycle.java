package com.telecom.support.servicecase;

import com.telecom.support.servicecase.AgentCaseQueryService.CaseStatus;
import com.telecom.support.servicecase.CaseRuleException.Reason;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The case transition table and closure/reopen windows (design 04-outbound backend §3.1, R7–R9; BRD §7.1, OUT-05, §14 #7).
 * The only source for O8 validation and for {@code allowedActions}. Pure apart from the injected {@link Clock}.
 */
@Component
@EnableConfigurationProperties(OutboundProperties.class)
class CaseLifecycle {

    /** How a RESOLVED case closes (R8). */
    enum ClosureType { CUSTOMER_CONFIRMED, WINDOW_ELAPSED }

    private static final Set<CaseStatus> WORKING = EnumSet.of(CaseStatus.IN_PROGRESS, CaseStatus.PENDING_CUSTOMER,
            CaseStatus.PENDING_EXTERNAL, CaseStatus.RESOLVED);

    /** §3.1 table: manual O8 targets per current status (never to itself). CREATED leaves only by claim/reassign. */
    private static final Map<CaseStatus, Set<CaseStatus>> TABLE = Map.of(
            CaseStatus.CREATED, EnumSet.noneOf(CaseStatus.class),
            CaseStatus.ASSIGNED, WORKING,
            CaseStatus.IN_PROGRESS, WORKING,
            CaseStatus.PENDING_CUSTOMER, WORKING,
            CaseStatus.PENDING_EXTERNAL, WORKING,
            CaseStatus.REOPENED, WORKING,
            CaseStatus.RESOLVED, EnumSet.of(CaseStatus.CLOSED, CaseStatus.REOPENED),
            CaseStatus.CLOSED, EnumSet.of(CaseStatus.REOPENED));

    private final OutboundProperties properties;
    private final Clock clock;

    CaseLifecycle(OutboundProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** RESOLVED and CLOSED: no claim, no reassign, no PUBLIC comment on CLOSED (R5, R6, R10). */
    static boolean terminal(CaseStatus status) {
        return status == CaseStatus.RESOLVED || status == CaseStatus.CLOSED;
    }

    /** The O8 targets from {@code from}, in lifecycle order (for {@code allowedActions.transitions}). */
    List<CaseStatus> targets(CaseStatus from) {
        return TABLE.getOrDefault(from, Set.of()).stream().filter(to -> to != from).sorted().toList();
    }

    /**
     * Validates one O8 transition (R7–R9): not in the table → {@code invalid_transition}; RESOLVED → CLOSED
     * {@code WINDOW_ELAPSED} before the verification window → {@code closure_condition_unmet}; CLOSED → REOPENED after the
     * reopen window → {@code reopen_window_expired}. {@code closureType} is required for → CLOSED (checked by the caller).
     */
    void check(CaseStatus from, CaseStatus to, ClosureType closureType, Instant resolvedAt, Instant closedAt) {
        if (from == to || !TABLE.getOrDefault(from, Set.of()).contains(to)) {
            throw new CaseRuleException(Reason.INVALID_TRANSITION, "Case " + from + " -> " + to);
        }
        Instant now = clock.instant();
        if (to == CaseStatus.CLOSED && closureType == ClosureType.WINDOW_ELAPSED
                && (resolvedAt == null || now.isBefore(resolvedAt.plus(properties.verificationWindow())))) {
            throw new CaseRuleException(Reason.CLOSURE_CONDITION_UNMET, "Case resolved at " + resolvedAt);
        }
        if (from == CaseStatus.CLOSED && to == CaseStatus.REOPENED
                && (closedAt == null || !now.isBefore(closedAt.plus(properties.reopenWindow())))) {
            throw new CaseRuleException(Reason.REOPEN_WINDOW_EXPIRED, "Case closed at " + closedAt);
        }
    }

    /** R8 job cutoff: RESOLVED rows with {@code resolved_at <= cutoff} may close {@code WINDOW_ELAPSED}. */
    Instant autoCloseCutoff() {
        return clock.instant().minus(properties.verificationWindow());
    }

    /** R9: a CLOSED case can still be reopened now. */
    boolean reopenable(Instant closedAt) {
        return closedAt != null && clock.instant().isBefore(closedAt.plus(properties.reopenWindow()));
    }

    /**
     * R4 {@code allowedActions.transitions} for a case the actor may change: the table targets, minus CLOSED→REOPENED once
     * the reopen window passed. WINDOW_ELAPSED timing is left to O8 (CUSTOMER_CONFIRMED is always possible).
     */
    List<CaseStatus> availableTargets(CaseStatus from, Instant closedAt) {
        if (from == CaseStatus.CLOSED && !reopenable(closedAt)) {
            return List.of();
        }
        return targets(from);
    }
}

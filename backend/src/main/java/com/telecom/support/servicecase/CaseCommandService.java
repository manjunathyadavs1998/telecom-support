package com.telecom.support.servicecase;

import com.telecom.support.audit.AuditService;
import com.telecom.support.common.error.ApiExceptions.NotFoundException;
import com.telecom.support.identity.CurrentUser;
import com.telecom.support.identity.Permissions;
import com.telecom.support.identity.UserDirectory;
import com.telecom.support.identity.UserDirectory.QueueAgent;
import com.telecom.support.servicecase.AgentCaseQueryService.CaseActivity;
import com.telecom.support.servicecase.AgentCaseQueryService.CaseStatus;
import com.telecom.support.servicecase.CaseLifecycle.ClosureType;
import com.telecom.support.servicecase.CaseRepository.OutboundCaseRow;
import com.telecom.support.servicecase.CaseRepository.TransitionValues;
import com.telecom.support.servicecase.CaseRuleException.Reason;
import com.telecom.support.servicecase.OutboundCaseQueryService.OutboundCaseDetail;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single writer of case assignment and status after creation (design 04-outbound backend §3.2, R5–R10; OUT-03,
 * OUT-04.1/04.3, OUT-05, BR-03, NFR-INTEG). Each command is one transaction: (1) lock the case row, (2) scope S (404),
 * (3) permission / ownership (403), version (409), rules (422), (4) one version-guarded UPDATE that must change one row,
 * (5) activity rows and the success audit. The row lock serialises commands on one case, so of N parallel claims
 * exactly one wins and the rest read an owner and get 409. Every command returns the committed state (XC-07).
 */
@Service
public class CaseCommandService {

    static final int REMARKS_MIN = 10;
    static final int REMARKS_MAX = 1000;
    static final String AUDIT_ASSIGNED = "CASE_ASSIGNED";
    static final String AUDIT_STATUS = "CASE_STATUS_CHANGED";

    /** 400 with one field error (R7 remarks / closure type), rendered as {@code errors[]}. */
    public static class CaseFieldException extends RuntimeException {
        private final String field;

        CaseFieldException(String field, String message) {
            super(message);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    private final CaseRepository cases;
    private final CaseActivityRepository activity;
    private final CaseLifecycle lifecycle;
    private final OutboundCaseQueryService queries;
    private final UserDirectory users;
    private final AuditService audit;
    private final Clock clock;

    CaseCommandService(CaseRepository cases, CaseActivityRepository activity, CaseLifecycle lifecycle,
                       OutboundCaseQueryService queries, UserDirectory users, AuditService audit, Clock clock) {
        this.cases = cases;
        this.activity = activity;
        this.lifecycle = lifecycle;
        this.queries = queries;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
    }

    /** O6 (R5): take an unassigned case. The owner's retry is a 200 no-op; someone else's case is 409. */
    @Transactional
    public OutboundCaseDetail claim(String reference) {
        CurrentUser actor = CurrentUser.require();
        OutboundCaseRow row = lock(reference, actor);
        CaseStatus status = CaseStatus.valueOf(row.status());
        if (row.assignedUserId() != null && row.assignedUserId() == actor.id()) {
            return queries.toDetail(row, actor);
        }
        if (CaseLifecycle.terminal(status)) {
            throw new CaseRuleException(Reason.CASE_CLOSED, "Claim of " + reference);
        }
        if (row.assignedUserId() != null) {
            throw new CaseRuleException(Reason.ALREADY_ASSIGNED, "Claim of " + reference);
        }
        CaseStatus to = status == CaseStatus.CREATED ? CaseStatus.ASSIGNED : status;
        Instant now = clock.instant();
        guarded(cases.assign(row.id(), row.version(), actor.id(), actor.teamId(), to.name(), now), reference);
        activity.insertAssigned(row.id(), actor.id(), null, actor.id(), null, now);
        if (to != status) {
            activity.insertStatusChanged(row.id(), actor.id(), status.name(), to.name(), now);
        }
        Map<String, Object> details = assignDetails("CLAIM", row, actor.id(), actor.teamId(), null);
        statusDetails(details, status, to);
        audit.record(AUDIT_ASSIGNED, actor.id(), actor.getUsername(), "CASE", reference, details);
        return reload(reference, actor);
    }

    /** O7 (R6, {@code case.assign.any}): assign to an ACTIVE queue agent; version-checked; team follows the agent. */
    @Transactional
    public OutboundCaseDetail reassign(String reference, long assigneeUserId, String reason, int version) {
        CurrentUser actor = CurrentUser.require();
        OutboundCaseRow row = lock(reference, actor);
        CaseStatus status = CaseStatus.valueOf(row.status());
        if (row.version() != version) {
            throw new CaseRuleException(Reason.STALE_VERSION, "Reassign of " + reference + " at " + version);
        }
        if (CaseLifecycle.terminal(status)) {
            throw new CaseRuleException(Reason.CASE_CLOSED, "Reassign of " + reference);
        }
        QueueAgent target = users.queueAgent(assigneeUserId)
                .orElseThrow(() -> new CaseRuleException(Reason.INVALID_ASSIGNEE, "Assignee " + assigneeUserId));
        if (row.assignedUserId() != null && row.assignedUserId() == assigneeUserId) {
            throw new CaseRuleException(Reason.NO_CHANGE, "Reassign of " + reference);
        }
        CaseStatus to = status == CaseStatus.CREATED ? CaseStatus.ASSIGNED : status;
        Instant now = clock.instant();
        guarded(cases.assign(row.id(), row.version(), assigneeUserId, target.teamId(), to.name(), now), reference);
        activity.insertAssigned(row.id(), actor.id(), row.assignedUserId(), assigneeUserId, reason, now);
        if (to != status) {
            activity.insertStatusChanged(row.id(), actor.id(), status.name(), to.name(), now);
        }
        Map<String, Object> details = assignDetails("REASSIGN", row, assigneeUserId, target.teamId(), reason);
        statusDetails(details, status, to);
        audit.record(AUDIT_ASSIGNED, actor.id(), actor.getUsername(), "CASE", reference, details);
        return reload(reference, actor);
    }

    /** O8 (R7–R9): a §3.1 transition by the assignee (or {@code case.assign.any}). */
    @Transactional
    public OutboundCaseDetail changeStatus(String reference, CaseStatus to, String remarks, ClosureType closureType,
                                           int version) {
        CurrentUser actor = CurrentUser.require();
        OutboundCaseRow row = lock(reference, actor);
        requireOwnerOrAny(row, actor, "status change");
        if (row.assignedUserId() == null) {
            throw new CaseRuleException(Reason.UNASSIGNED, "Status change of " + reference);
        }
        if (row.version() != version) {
            throw new CaseRuleException(Reason.STALE_VERSION, "Status change of " + reference + " at " + version);
        }
        CaseStatus from = CaseStatus.valueOf(row.status());
        String text = remarks == null ? null : remarks.strip();
        if (to == CaseStatus.CLOSED && closureType == null) {
            throw new CaseFieldException("closureType", "Choose how the case closes.");
        }
        boolean remarksRequired = to == CaseStatus.RESOLVED || to == CaseStatus.REOPENED
                || (to == CaseStatus.CLOSED && closureType == ClosureType.CUSTOMER_CONFIRMED);
        if (remarksRequired && (text == null || text.length() < REMARKS_MIN)) {
            throw new CaseFieldException("remarks", "Enter remarks of at least " + REMARKS_MIN + " characters.");
        }
        if (text != null && text.length() > REMARKS_MAX) {
            throw new CaseFieldException("remarks", "Use at most " + REMARKS_MAX + " characters.");
        }
        lifecycle.check(from, to, closureType, row.resolvedAt(), row.closedAt());

        Instant now = clock.instant();
        TransitionValues values = switch (to) {
            case RESOLVED -> new TransitionValues(to.name(), now, actor.id(), text, null, null, row.reopenCount());
            case CLOSED -> new TransitionValues(to.name(), row.resolvedAt(), row.resolvedByUserId(),
                    row.resolutionRemarks(), now, closureType.name(), row.reopenCount());
            case REOPENED -> new TransitionValues(to.name(), null, null, null, null, null, row.reopenCount() + 1);
            default -> new TransitionValues(to.name(), row.resolvedAt(), row.resolvedByUserId(),
                    row.resolutionRemarks(), row.closedAt(), row.closureType(), row.reopenCount());
        };
        guarded(cases.transition(row.id(), row.version(), values, now), reference);
        activity.insertStatusChanged(row.id(), actor.id(), from.name(), to.name(), now);
        if (text != null && !text.isEmpty()) {
            activity.insertNote(row.id(), actor.id(), "INTERNAL", text, now);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from", from.name());
        details.put("to", to.name());
        if (text != null && !text.isEmpty()) {
            details.put("remarks", text);
        }
        if (closureType != null && to == CaseStatus.CLOSED) {
            details.put("closureType", closureType.name());
        }
        details.put("source", "request");
        audit.record(AUDIT_STATUS, actor.id(), actor.getUsername(), "CASE", reference, details);
        return reload(reference, actor);
    }

    /**
     * O9 (R10): INTERNAL needs {@code case.note.internal}; PUBLIC needs {@code case.status.update} and the assignee (or
     * {@code case.assign.any}), and not on a CLOSED case. Bumps {@code updated_at}/{@code version}; no audit.
     */
    @Transactional
    public CaseActivity addNote(String reference, String visibility, String text) {
        CurrentUser actor = CurrentUser.require();
        OutboundCaseRow row = lock(reference, actor);
        String body = text == null ? "" : text.strip();
        if (body.isEmpty() || body.length() > 2000) {
            throw new CaseFieldException("text", "Enter a note of 1 to 2000 characters.");
        }
        if ("INTERNAL".equals(visibility)) {
            if (!actor.has(Permissions.CASE_NOTE_INTERNAL)) {
                throw new AccessDeniedException("Internal note on " + reference + " needs case.note.internal");
            }
        } else if ("PUBLIC".equals(visibility)) {
            if (!actor.has(Permissions.CASE_STATUS_UPDATE)) {
                throw new AccessDeniedException("Public comment on " + reference + " needs case.status.update");
            }
            requireOwnerOrAny(row, actor, "public comment");
            if (CaseStatus.valueOf(row.status()) == CaseStatus.CLOSED) {
                throw new CaseRuleException(Reason.CASE_CLOSED, "Public comment on " + reference);
            }
        } else {
            throw new CaseFieldException("visibility", "Visibility must be PUBLIC or INTERNAL.");
        }
        Instant now = clock.instant();
        guarded(cases.touch(row.id(), row.version(), now), reference);
        activity.insertNote(row.id(), actor.id(), visibility, body, now);
        return new CaseActivity("NOTE", visibility, actor.displayName(), null, null, body, now);
    }

    /** §3.2 (1)–(2): lock the row; unknown or out of scope → 404 (existence not leaked). */
    private OutboundCaseRow lock(String reference, CurrentUser actor) {
        OutboundScope scope = OutboundScope.of(actor);
        return cases.lockForCommand(reference, actor.id())
                .filter(r -> scope.covers(r.assignedUserId(), r.assignedTeamId()))
                .orElseThrow(() -> new NotFoundException("Outbound case " + reference + " not in scope"));
    }

    private static void requireOwnerOrAny(OutboundCaseRow row, CurrentUser actor, String what) {
        boolean owner = row.assignedUserId() != null && row.assignedUserId() == actor.id();
        if (!owner && !actor.has(Permissions.CASE_ASSIGN_ANY)) {
            throw new AccessDeniedException(what + " on " + row.reference() + " by a non-owner (not_owner)");
        }
    }

    /** §3.2 (4): 0 rows changed despite the lock means the version moved (defence in depth). */
    private static void guarded(boolean changed, String reference) {
        if (!changed) {
            throw new CaseRuleException(Reason.STALE_VERSION, "Guarded update of " + reference);
        }
    }

    private OutboundCaseDetail reload(String reference, CurrentUser actor) {
        return queries.toDetail(cases.findOutbound(reference, actor.id()).orElseThrow(), actor);
    }

    private static Map<String, Object> assignDetails(String mode, OutboundCaseRow row, long toUserId, Long toTeamId,
                                                     String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("mode", mode);
        details.put("fromUserId", row.assignedUserId());
        details.put("toUserId", toUserId);
        details.put("fromTeamId", row.assignedTeamId());
        details.put("toTeamId", toTeamId);
        if (reason != null) {
            details.put("reason", reason);
        }
        return details;
    }

    private static void statusDetails(Map<String, Object> details, CaseStatus from, CaseStatus to) {
        if (from != to) {
            details.put("statusFrom", from.name());
            details.put("statusTo", to.name());
        }
    }
}

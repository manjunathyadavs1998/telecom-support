package com.telecom.support.reactivation;

import com.telecom.support.audit.AuditEvent.Outcome;
import com.telecom.support.audit.AuditService;
import com.telecom.support.common.error.ApiExceptions.ApiException;
import com.telecom.support.common.error.ApiExceptions.BadRequestException;
import com.telecom.support.common.error.ApiExceptions.ConflictException;
import com.telecom.support.common.error.ProblemDetailFactory.Problem;
import com.telecom.support.common.reference.ReferenceGenerator;
import com.telecom.support.common.security.ActorProvider;
import com.telecom.support.common.security.ActorProvider.Actor;
import com.telecom.support.customer.AccountLookup;
import com.telecom.support.customer.AccountLookup.CustomerAccount;
import com.telecom.support.customer.AccountLookup.SuspensionReason;
import com.telecom.support.customer.VerifiedCustomer;
import com.telecom.support.identity.UserDirectory;
import com.telecom.support.reactivation.ProvisioningClient.ProvisioningRejectedException;
import com.telecom.support.reactivation.ProvisioningClient.ProvisioningUnavailableException;
import com.telecom.support.reactivation.ProvisioningClient.RestoreResult;
import com.telecom.support.reactivation.ReactivationEligibilityService.EligibilityReason;
import com.telecom.support.reactivation.ReactivationEligibilityService.ReactivationEligibility;
import com.telecom.support.reactivation.ReactivationRepository.NewRequest;
import com.telecom.support.reactivation.ReactivationRepository.RequestRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Create I14 and history I15 of temporary reactivations (design 03-inbound backend R14, R15, §3.1, §7; INB-04.1..04.4,
 * INB-04.6, INB-04.7, NFR-REL, XC-06/07). The account always comes from the {@link VerifiedCustomer}.
 *
 * <p>I14: the {@code REA} reference is drawn first (own transaction). tx1 locks the agent's {@code users} row, then
 * the account row (fixed order; serialises the per-agent and per-account limits), re-runs R13, inserts PENDING
 * (snapshot of reason and suspended MSISDNs, the check id, {@code next_attempt_at} so a crash before the provider call
 * is reconciled) with {@code REACTIVATION_REQUESTED}, and commits. Ineligible (or hours above the policy) → 422 with
 * {@code reasons[]} and an independent {@code REACTIVATION_REJECTED}; an open request (or the open-request UK) → 409.
 * Then {@code restore} runs outside any transaction, and {@link ReactivationTransitionService} records the outcome:
 * confirmed → ACTIVE, 201; rejected → FAILED, 422; unavailable → FAILED, 503; timeout or any other failure → PENDING
 * stays ({@code provider_unknown}, reconciled), 503 with the reference. Accounts and subscriptions are never changed.
 */
@Service
public class ReactivationService {

    static final String ACCOUNT_ENTITY = "ACCOUNT";
    static final String SOURCE_REQUEST = "request";
    static final int DEFAULT_WINDOW_MONTHS = 3;
    static final long MAX_WINDOW_DAYS = 366;
    /** §3.1 backoff: the first reconcile of a request whose restore outcome is not known. */
    static final Duration FIRST_RECONCILE_AFTER = Duration.ofMinutes(1);

    private static final Logger log = LoggerFactory.getLogger(ReactivationService.class);

    /** The {@code ReactivationStatus} schema. */
    public enum ReactivationStatus { PENDING, ACTIVE, EXPIRING, EXPIRED, ENDED, FAILED }

    /** What the agent submits (the {@code ReactivationRequest} schema, already bean-validated). */
    public record ReactivationDraft(Integer hours, String reason, String approvalReference) {}

    /** The {@code Reactivation} schema. */
    public record Reactivation(String reference, ReactivationStatus status, SuspensionReason suspensionReason,
                               List<String> msisdns, int requestedHours, String reason, String approvalReference,
                               String requestedByName, String failureReason, Instant createdAt, Instant activatedAt,
                               Instant expiresAt, Instant endedAt, boolean needsAttention, String endReason) {}

    /** The {@code ReactivationPage} schema. */
    public record ReactivationPage(List<Reactivation> items, int page, int size, long totalItems, int totalPages) {}

    /**
     * A reactivation not performed: the {@code ReactivationProblem} schema ({@code reasons} for an eligibility 422,
     * {@code reference} and {@code reactivationStatus} when a request row exists).
     */
    public static class ReactivationProblemException extends ApiException {

        private final transient List<EligibilityReason> reasons;
        private final String reference;
        private final ReactivationStatus reactivationStatus;

        ReactivationProblemException(Problem problem, String message, String userDetail,
                                     List<EligibilityReason> reasons, String reference,
                                     ReactivationStatus reactivationStatus) {
            super(problem, message, userDetail);
            this.reasons = List.copyOf(reasons);
            this.reference = reference;
            this.reactivationStatus = reactivationStatus;
        }

        public List<EligibilityReason> reasons() {
            return reasons;
        }

        public String reference() {
            return reference;
        }

        public ReactivationStatus reactivationStatus() {
            return reactivationStatus;
        }
    }

    /** tx1 refused by R13/R14 (rolled back; audited afterwards in its own transaction). */
    private static final class Ineligible extends RuntimeException {
        private final String accountRef;
        private final List<EligibilityReason> reasons;

        Ineligible(String accountRef, List<EligibilityReason> reasons) {
            super("Reactivation refused: " + reasons, null, false, false);
            this.accountRef = accountRef;
            this.reasons = reasons;
        }
    }

    private final ReactivationEligibilityService eligibility;
    private final ReactivationRepository requests;
    private final ReactivationTransitionService transitions;
    private final ProvisioningClient provisioning;
    private final AccountLookup accounts;
    private final UserDirectory users;
    private final ReferenceGenerator references;
    private final AuditService audit;
    private final ActorProvider actors;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ZoneId zone;

    ReactivationService(ReactivationEligibilityService eligibility, ReactivationRepository requests,
                        ReactivationTransitionService transitions, ProvisioningClient provisioning,
                        AccountLookup accounts, UserDirectory users, ReferenceGenerator references,
                        AuditService audit, ActorProvider actors, PlatformTransactionManager transactionManager,
                        Clock clock, @Value("${app.display-time-zone:Asia/Kolkata}") ZoneId zone) {
        this.eligibility = eligibility;
        this.requests = requests;
        this.transitions = transitions;
        this.provisioning = provisioning;
        this.accounts = accounts;
        this.users = users;
        this.references = references;
        this.audit = audit;
        this.actors = actors;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.zone = zone;
    }

    /** I14 (R14, §3.1). Never inside a caller's transaction: the provider call must run outside any. */
    public Reactivation create(VerifiedCustomer verified, ReactivationDraft draft) {
        Actor actor = actors.currentActor().orElseThrow();
        String reference = references.next(ReferenceGenerator.REACTIVATION);
        RequestRow row;
        try {
            row = tx.execute(status -> insertPending(verified, draft, reference, actor));
        } catch (Ineligible refused) {
            audit.recordIndependently("REACTIVATION_REJECTED", Outcome.FAILURE, actor.id(), actor.username(),
                    ACCOUNT_ENTITY, refused.accountRef, Map.of("reasons", refused.reasons));
            throw new ReactivationProblemException(Problem.BUSINESS_RULE,
                    "Reactivation for account " + verified.accountId() + " refused: " + refused.reasons,
                    "This account cannot be temporarily reactivated now.", refused.reasons, null, null);
        } catch (DuplicateKeyException openRequest) {
            throw new ConflictException("Account " + verified.accountId() + " got an open reactivation concurrently");
        }

        Instant until = clock.instant().plus(Duration.ofHours(row.requestedHours()));
        RestoreResult restored;
        try {
            restored = provisioning.restore(row.reference(), row.msisdns(), until);
        } catch (ProvisioningRejectedException e) {
            transitions.fail(row, "provider_rejected", SOURCE_REQUEST, actor);
            throw problem(row, Problem.BUSINESS_RULE, "Provisioning rejected " + row.reference(),
                    "The provisioning system refused the reactivation.");
        } catch (ProvisioningUnavailableException e) {
            transitions.fail(row, "provider_unavailable", SOURCE_REQUEST, actor);
            throw problem(row, Problem.DEPENDENCY_UNAVAILABLE, "Provisioning unavailable for " + row.reference(),
                    "The provisioning system is unavailable. Please try again later.");
        } catch (RuntimeException unknown) { // timeout, 5xx or anything else: the outcome is not known
            log.warn("Reactivation {}: restore outcome unknown ({})", row.reference(),
                    unknown.getClass().getSimpleName());
            transitions.markOutcomeUnknown(row, clock.instant().plus(FIRST_RECONCILE_AFTER));
            throw problem(row, Problem.DEPENDENCY_UNAVAILABLE, "Provisioning outcome unknown for " + row.reference(),
                    "The provisioning system did not confirm the reactivation in time. It is checked automatically;"
                            + " quote the reference.");
        }
        Instant activatedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        transitions.activate(row, restored.providerRef(), activatedAt,
                activatedAt.plus(Duration.ofHours(row.requestedHours())), SOURCE_REQUEST, actor);
        RequestRow current = requests.findById(row.id()).orElseThrow();
        ReactivationStatus status = ReactivationStatus.valueOf(current.status());
        if (status != ReactivationStatus.ACTIVE && status != ReactivationStatus.EXPIRING) {
            throw problem(current, Problem.DEPENDENCY_UNAVAILABLE, "Reactivation " + row.reference()
                    + " is " + status + " after a confirmed restore", "The reactivation could not be confirmed.");
        }
        return view(current, users.displayNames(List.of(current.requestedByUserId())));
    }

    /** I15: the verified customer's account history, {@code created_at} window, newest first. */
    @Transactional(readOnly = true)
    public ReactivationPage history(VerifiedCustomer verified, LocalDate from, LocalDate to, int page, int size) {
        Instant[] window = window(from, to);
        long total = requests.countPage(verified.accountId(), window[0], window[1]);
        List<RequestRow> rows = total == 0 ? List.of()
                : requests.findPage(verified.accountId(), window[0], window[1], size, (long) page * size);
        Map<Long, String> names = users.displayNames(rows.stream().map(RequestRow::requestedByUserId).toList());
        List<Reactivation> items = rows.stream().map(r -> view(r, names)).toList();
        int totalPages = (int) ((total + size - 1) / size);
        return new ReactivationPage(items, page, size, total, totalPages);
    }

    /** tx1 of R14: locks (user, then account), R13 again, hours, insert PENDING, audit. */
    private RequestRow insertPending(VerifiedCustomer verified, ReactivationDraft draft, String reference,
                                     Actor actor) {
        users.lockForUpdate(verified.agentUserId());
        CustomerAccount account = accounts.lockForUpdate(verified.accountId());
        ReactivationEligibility result = eligibility.evaluate(account, verified.agentUserId());
        if (result.reasons().contains(EligibilityReason.OPEN_REQUEST)) {
            throw new ConflictException("Account " + account.accountId() + " has the open reactivation "
                    + result.openRequestReference());
        }
        List<EligibilityReason> reasons = new ArrayList<>(result.reasons());
        Integer maxHours = result.maxHours();
        int hours = draft.hours() != null ? draft.hours()
                : maxHours == null ? ReactivationEligibilityService.MAX_HOURS
                : Math.min(ReactivationEligibilityService.MAX_HOURS, maxHours);
        if (maxHours != null && hours > maxHours) {
            reasons.add(EligibilityReason.HOURS_EXCEED_POLICY);
        }
        if (!reasons.isEmpty()) {
            throw new Ineligible(account.accountRef(), List.copyOf(reasons));
        }
        Instant now = clock.instant();
        String approval = draft.approvalReference() == null || draft.approvalReference().isBlank() ? null
                : draft.approvalReference().strip();
        long id = requests.insert(new NewRequest(reference, account.accountId(), result.suspensionReason().name(),
                result.suspendedMsisdns(), hours, draft.reason().strip(), approval, verified.checkId(),
                verified.agentUserId(), now.plus(FIRST_RECONCILE_AFTER), now));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("accountRef", account.accountRef());
        details.put("suspensionReason", result.suspensionReason().name());
        details.put("hours", hours);
        details.put("reason", draft.reason().strip());
        details.put("approvalReference", approval);
        details.put("checkId", verified.checkId());
        audit.record("REACTIVATION_REQUESTED", actor.id(), actor.username(), ReactivationTransitionService.ENTITY,
                reference, details);
        return requests.findById(id).orElseThrow();
    }

    /** A provider-side problem: the request's reference and its status as stored now (FAILED or PENDING). */
    private ReactivationProblemException problem(RequestRow row, Problem problem, String message,
                                                 String userDetail) {
        ReactivationStatus status = requests.findById(row.id())
                .map(r -> ReactivationStatus.valueOf(r.status())).orElse(null);
        return new ReactivationProblemException(problem, message, userDetail, List.of(), row.reference(), status);
    }

    private static Reactivation view(RequestRow r, Map<Long, String> names) {
        return new Reactivation(r.reference(), ReactivationStatus.valueOf(r.status()),
                SuspensionReason.valueOf(r.suspensionReason()), r.msisdns(), r.requestedHours(), r.reasonText(),
                r.approvalReference(), names.getOrDefault(r.requestedByUserId(), ""), r.failureReason(),
                r.createdAt(), r.activatedAt(), r.expiresAt(), r.endedAt(), r.needsAttention(), r.endReason());
    }

    /** Inclusive display-zone dates → UTC {@code [from, to)}; default the 3 months to today; ≤ 366 days (BR-05/06). */
    private Instant[] window(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : end.minusMonths(DEFAULT_WINDOW_MONTHS);
        if (start.isAfter(end)) {
            throw new BadRequestException("Reactivation history from " + start + " after to " + end,
                    "The start date must not be after the end date.");
        }
        if (ChronoUnit.DAYS.between(start, end) > MAX_WINDOW_DAYS) {
            throw new BadRequestException("Reactivation history range " + start + ".." + end + " over "
                    + MAX_WINDOW_DAYS + " days", "The date range can be at most 366 days.");
        }
        return new Instant[] {start.atStartOfDay(zone).toInstant(), end.plusDays(1).atStartOfDay(zone).toInstant()};
    }
}

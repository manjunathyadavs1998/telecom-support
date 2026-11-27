package com.telecom.support.reactivation;

import com.telecom.support.common.error.ProblemDetailFactory;
import com.telecom.support.common.idempotency.IdempotencyService;
import com.telecom.support.customer.VerifiedCustomer;
import com.telecom.support.reactivation.ReactivationEligibilityService.ReactivationEligibility;
import com.telecom.support.reactivation.ReactivationService.ReactivationDraft;
import com.telecom.support.reactivation.ReactivationService.ReactivationPage;
import com.telecom.support.reactivation.ReactivationService.ReactivationProblemException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/agent/customers/{customerId}/reactivation…} — I13 eligibility, I14 create, I15 history (design
 * 03-inbound backend §4, §5, R13–R15; openapi.yaml; INB-04). Every handler needs {@code customer.lookup} (inbound
 * agents only) plus its own permission; the account comes from the {@link VerifiedCustomer} of the verified-scope
 * interceptor (R5), never from the path id or the body.
 */
@RestController
@RequestMapping("/api/v1/agent/customers/{customerId}")
public class ReactivationController {

    /** Idempotency operation of I14 (design §5). */
    static final String REACTIVATION_CREATE_OPERATION = "REACTIVATION_CREATE";

    private static final Logger log = LoggerFactory.getLogger(ReactivationController.class);

    /** The {@code ReactivationRequest} schema. */
    public record ReactivationRequest(
            @Min(1) @Max(24) Integer hours,
            @NotBlank @Size(min = 10, max = 500) String reason,
            @Size(max = 64) String approvalReference) {}

    /** The canonical idempotent request of I14: path variables and body (02 D6). */
    record KeyedRequest(long customerId, ReactivationRequest body) {}

    private final ReactivationEligibilityService eligibility;
    private final ReactivationService reactivations;
    private final IdempotencyService idempotency;
    private final ProblemDetailFactory problems;

    ReactivationController(ReactivationEligibilityService eligibility, ReactivationService reactivations,
                           IdempotencyService idempotency, ProblemDetailFactory problems) {
        this.eligibility = eligibility;
        this.reactivations = reactivations;
        this.idempotency = idempotency;
        this.problems = problems;
    }

    /** I13 (R13): whether a temporary reactivation may be requested now, and every reason why not. */
    @GetMapping("/reactivation-eligibility")
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CUSTOMER_LOOKUP)"
            + " and hasAuthority(T(com.telecom.support.identity.Permissions).SERVICE_REACTIVATE)")
    public ReactivationEligibility eligibility(VerifiedCustomer verified) {
        return eligibility.eligibilityOf(verified);
    }

    /**
     * I14 (R14, §3.1): 201 only once the provider confirmed (ACTIVE). Idempotent ({@code REACTIVATION_CREATE}, hash of
     * the customer id and the body); the interceptor runs first, so a replay after the check expired is 403.
     */
    @PostMapping(path = "/reactivations", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CUSTOMER_LOOKUP)"
            + " and hasAuthority(T(com.telecom.support.identity.Permissions).SERVICE_REACTIVATE)")
    public ResponseEntity<?> create(
            VerifiedCustomer verified,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ReactivationRequest body) {
        return idempotency.execute(verified.agentUserId(), REACTIVATION_CREATE_OPERATION, idempotencyKey,
                new KeyedRequest(verified.customerId(), body),
                () -> ResponseEntity.status(HttpStatus.CREATED).body(reactivations.create(verified,
                        new ReactivationDraft(body.hours(), body.reason(), body.approvalReference()))));
    }

    /** I15: the account's reactivations, {@code createdAt} window (default 3 months), newest first. */
    @GetMapping("/reactivations")
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CUSTOMER_LOOKUP)"
            + " and hasAuthority(T(com.telecom.support.identity.Permissions).CUSTOMER_PROFILE_READ)")
    public ReactivationPage history(
            VerifiedCustomer verified,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return reactivations.history(verified, from, to, page, size);
    }

    /**
     * 422 / 503 {@code ReactivationProblem}: the row's problem plus {@code reasons} (eligibility), {@code reference} and
     * {@code reactivationStatus} (a request row exists).
     */
    @ExceptionHandler(ReactivationProblemException.class)
    ResponseEntity<ProblemDetail> reactivationProblem(ReactivationProblemException ex, HttpServletRequest request) {
        log.debug("{} on {}: {}", ex.problem(), request.getRequestURI(), ex.getMessage());
        ProblemDetail pd = problems.create(ex.problem(), request.getRequestURI());
        ex.userDetail().ifPresent(pd::setDetail);
        if (!ex.reasons().isEmpty()) {
            pd.setProperty("reasons", ex.reasons());
        }
        if (ex.reference() != null) {
            pd.setProperty("reference", ex.reference());
            pd.setProperty("reactivationStatus", ex.reactivationStatus());
        }
        return ResponseEntity.status(pd.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }
}

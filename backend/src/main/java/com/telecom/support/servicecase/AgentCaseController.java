package com.telecom.support.servicecase;

import com.telecom.support.common.idempotency.IdempotencyService;
import com.telecom.support.customer.VerifiedCustomer;
import com.telecom.support.servicecase.AgentCaseQueryService.AgentCaseDetail;
import com.telecom.support.servicecase.AgentCaseQueryService.AgentCasePage;
import com.telecom.support.servicecase.AgentCaseQueryService.AgentCaseSort;
import com.telecom.support.servicecase.AgentCaseQueryService.CaseStatus;
import com.telecom.support.servicecase.AgentCaseService.AgentCaseDraft;
import com.telecom.support.servicecase.AgentCaseService.AgentCategory;
import com.telecom.support.servicecase.CustomerCaseService.PreferredContact;
import com.telecom.support.servicecase.CustomerCaseService.Severity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/agent/**} case endpoints of the inbound workspace — I9 list, I10 detail, I11 categories, I12 create on
 * behalf (design 03-inbound backend §4, §5, R8–R12; openapi.yaml; INB-02, INB-03, BR-04). Every handler needs
 * {@code customer.lookup} (inbound agents only) plus its own permission; the account comes from the
 * {@link VerifiedCustomer} of the verified-scope interceptor (R5), never from the path id. Unknown JSON fields
 * ({@code accountId}, {@code channel}, {@code contactMsisdn}, …) are ignored.
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentCaseController {

    /** Idempotency operation of I12 (design §5). */
    static final String CASE_CREATE_ON_BEHALF_OPERATION = "CASE_CREATE_ONBEHALF";

    /** The {@code AgentCaseCreateRequest} schema (no attachments, OQ 5). */
    public record AgentCaseCreateRequest(
            @NotBlank @Size(max = 32) String categoryCode,
            @Size(min = 1, max = 32) String subcategoryCode,
            Long subscriptionId,
            @NotBlank @Size(min = 5, max = 150) String subject,
            @NotBlank @Size(min = 10, max = 4000) String customerStatement,
            @Size(max = 4000) String agentNotes,
            Severity severity,
            @Size(min = 5, max = 200) String severityReason,
            @NotNull PreferredContact preferredContact) {}

    /** The canonical idempotent request of I12: path variables and body (02 D6). */
    record KeyedRequest(long customerId, AgentCaseCreateRequest body) {}

    private final AgentCaseQueryService queries;
    private final AgentCaseService cases;
    private final IdempotencyService idempotency;

    AgentCaseController(AgentCaseQueryService queries, AgentCaseService cases, IdempotencyService idempotency) {
        this.queries = queries;
        this.cases = cases;
        this.idempotency = idempotency;
    }

    /** I11 (R11): categories for agents; INTERNAL ones only with {@code case.category.internal}. */
    @GetMapping("/case-categories")
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CUSTOMER_LOOKUP)"
            + " and hasAuthority(T(com.telecom.support.identity.Permissions).CASE_CREATE_ON_BEHALF)")
    public List<AgentCategory> categories() {
        return cases.categories();
    }

    /**
     * I12 (R12): a case on the verified customer's account, idempotent ({@code CASE_CREATE_ONBEHALF}, hash of the
     * customer id and the body). The interceptor runs first, so a replay after the check expired is 403.
     */
    @PostMapping(path = "/customers/{customerId}/cases", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CUSTOMER_LOOKUP)"
            + " and hasAuthority(T(com.telecom.support.identity.Permissions).CASE_CREATE_ON_BEHALF)")
    public ResponseEntity<?> createOnBehalf(
            VerifiedCustomer verified,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AgentCaseCreateRequest body) {
        return idempotency.execute(verified.agentUserId(), CASE_CREATE_ON_BEHALF_OPERATION, idempotencyKey,
                new KeyedRequest(verified.customerId(), body),
                () -> ResponseEntity.status(HttpStatus.CREATED).body(cases.createOnBehalf(verified, new AgentCaseDraft(
                        body.categoryCode(), body.subcategoryCode(), body.subscriptionId(), body.subject(),
                        body.customerStatement(), body.agentNotes(), body.severity(), body.severityReason(),
                        body.preferredContact()))));
    }

    /** I9 (R8): window default 3 months, optional statuses and exact reference, paged, sort whitelist. */
    @GetMapping("/customers/{customerId}/cases")
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CUSTOMER_LOOKUP)"
            + " and hasAuthority(T(com.telecom.support.identity.Permissions).CASE_READ)")
    public AgentCasePage listCases(
            VerifiedCustomer verified,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "status", required = false) @Size(max = 8) List<CaseStatus> statuses,
            @RequestParam(required = false) @Size(max = 24)
            @Pattern(regexp = "^SR-\\d{8}-\\d{6,}$", message = "must be a service request reference") String reference,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        return queries.listCases(verified, from, to, statuses, reference, page, size, AgentCaseSort.fromParam(sort));
    }

    /** I10 (R9, R10): a case of the verified customer's account, else 404. */
    @GetMapping("/customers/{customerId}/cases/{reference}")
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CUSTOMER_LOOKUP)"
            + " and hasAuthority(T(com.telecom.support.identity.Permissions).CASE_READ)")
    public AgentCaseDetail caseDetail(VerifiedCustomer verified, @PathVariable String reference) {
        return queries.caseDetail(verified, reference);
    }
}

package com.telecom.support.servicecase;

import com.telecom.support.customer.VerifiedCustomer;
import com.telecom.support.servicecase.AgentCaseQueryService.AgentCaseDetail;
import com.telecom.support.servicecase.AgentCaseQueryService.AgentCasePage;
import com.telecom.support.servicecase.AgentCaseQueryService.AgentCaseSort;
import com.telecom.support.servicecase.AgentCaseQueryService.CaseStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/agent/**} case endpoints of the inbound workspace — I9 list, I10 detail (design 03-inbound backend
 * §4, §5, R8–R10; openapi.yaml; INB-02, BR-04). Every handler needs {@code customer.lookup} (inbound agents only) plus
 * its own permission; the account comes from the {@link VerifiedCustomer} of the verified-scope interceptor (R5), never
 * from the path id.
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentCaseController {

    private final AgentCaseQueryService queries;

    AgentCaseController(AgentCaseQueryService queries) {
        this.queries = queries;
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

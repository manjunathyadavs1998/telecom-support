package com.telecom.support.reactivation;

import com.telecom.support.customer.VerifiedCustomer;
import com.telecom.support.reactivation.ReactivationEligibilityService.ReactivationEligibility;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/agent/customers/{customerId}/reactivation…} — I13 eligibility (design 03-inbound backend §4, §5, R13;
 * openapi.yaml; INB-04). Every handler needs {@code customer.lookup} (inbound agents only) plus its own permission; the
 * account comes from the {@link VerifiedCustomer} of the verified-scope interceptor (R5), never from the path id.
 */
@RestController
@RequestMapping("/api/v1/agent/customers/{customerId}")
public class ReactivationController {

    private final ReactivationEligibilityService eligibility;

    ReactivationController(ReactivationEligibilityService eligibility) {
        this.eligibility = eligibility;
    }

    /** I13 (R13): whether a temporary reactivation may be requested now, and every reason why not. */
    @GetMapping("/reactivation-eligibility")
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CUSTOMER_LOOKUP)"
            + " and hasAuthority(T(com.telecom.support.identity.Permissions).SERVICE_REACTIVATE)")
    public ReactivationEligibility eligibility(VerifiedCustomer verified) {
        return eligibility.eligibilityOf(verified);
    }
}

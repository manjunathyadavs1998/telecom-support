package com.telecom.support.customer;

import com.telecom.support.customer.CustomerLookupService.CustomerMatch;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/agent/**} customer endpoints of the inbound workspace (design 03-inbound backend §4, §5; openapi.yaml).
 * Every handler needs {@code customer.lookup} (inbound agents only) plus its own permission.
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentCustomerController {

    /** The {@code CustomerSearch} schema; the MSISDN goes in the body so it never appears in a URL. */
    public record CustomerSearch(@NotNull @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "must be an E.164 number")
                                 String msisdn) {

        /** Never print the full number (NFR-PRIV). */
        @Override
        public String toString() {
            return "CustomerSearch[msisdn=***]";
        }
    }

    private final CustomerLookupService lookups;

    public AgentCustomerController(CustomerLookupService lookups) {
        this.lookups = lookups;
    }

    /** I1 (R1). */
    @PostMapping(path = "/customer-searches", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CUSTOMER_LOOKUP)")
    public List<CustomerMatch> search(@Valid @RequestBody CustomerSearch body) {
        return lookups.search(body.msisdn());
    }
}

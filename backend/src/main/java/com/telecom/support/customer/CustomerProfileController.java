package com.telecom.support.customer;

import com.telecom.support.customer.CustomerService.Profile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/customers/me/profile} — E1, E2 (design 02-customer backend §4, §5, R1, R2, R16; openapi.yaml). Both
 * need the permission and a customer principal with a linked customer ({@code @customerScope.isCustomer()}), so staff
 * (INBOUND_AGENT holds {@code customer.profile.read}) and unlinked customers get 403. The customer is never taken from
 * the request; unknown JSON fields such as {@code customerId} are ignored.
 */
@RestController
@RequestMapping("/api/v1/customers/me/profile")
public class CustomerProfileController {

    /**
     * The {@code ProfileUpdate} schema. {@code aliasName} is a required key: missing → 400, {@code null} clears it.
     * Jackson calls the setter for a present key only (also for JSON {@code null}), so a missing key leaves the
     * {@link Optional} itself null and fails {@code @NotNull}; JSON {@code null} becomes {@link Optional#empty()}.
     */
    public static final class ProfileUpdate {

        @NotNull(message = "is required (null clears the alias)")
        private Optional<@Size(min = 1, max = 60)
                         @Pattern(regexp = "^[\\p{L}\\p{N} .'-]+$",
                                  message = "may contain only letters, digits, spaces and . ' -") String> aliasName;

        @NotNull
        private Integer version;

        public void setAliasName(String aliasName) {
            this.aliasName = Optional.ofNullable(aliasName);
        }

        public void setVersion(Integer version) {
            this.version = version;
        }

        Optional<String> aliasName() {
            return aliasName;
        }

        Integer version() {
            return version;
        }
    }

    private final CustomerService customers;

    public CustomerProfileController(CustomerService customers) {
        this.customers = customers;
    }

    @GetMapping
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CUSTOMER_PROFILE_READ)"
            + " and @customerScope.isCustomer()")
    public Profile getMyProfile() {
        return customers.myProfile();
    }

    @PatchMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CUSTOMER_PROFILE_UPDATE)"
            + " and @customerScope.isCustomer()")
    public Profile updateMyProfile(@Valid @RequestBody ProfileUpdate update) {
        return customers.updateMyAlias(update.aliasName().orElse(null), update.version());
    }
}

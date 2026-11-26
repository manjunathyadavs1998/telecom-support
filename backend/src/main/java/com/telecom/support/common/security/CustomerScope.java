package com.telecom.support.common.security;

import org.springframework.security.access.AccessDeniedException;

/**
 * The signed-in customer's scope (design 02-customer backend §4, R1; BR-10, CUS-01.3). Implemented by {@code identity}
 * as the bean {@code customerScope}, so feature modules read the principal's customer id without depending on
 * {@code identity} (ADR 0001). A customer's id always comes from here, never from the request.
 *
 * <p>{@code /customers/me/**} endpoints guard with {@code @PreAuthorize("hasAuthority(...) and @customerScope.isCustomer()")}.
 */
public interface CustomerScope {

    /** True only for an authenticated {@code userType=CUSTOMER} principal with a linked {@code customer_id}. */
    boolean isCustomer();

    /**
     * The linked customer id of the current principal.
     *
     * @throws AccessDeniedException when {@link #isCustomer()} is false (staff, unlinked customer or anonymous)
     */
    long requireCustomerId();
}

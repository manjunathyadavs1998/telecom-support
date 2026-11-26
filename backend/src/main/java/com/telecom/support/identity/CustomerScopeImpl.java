package com.telecom.support.identity;

import com.telecom.support.common.security.CustomerScope;
import com.telecom.support.identity.CurrentUser.UserType;
import java.util.Optional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * {@link CustomerScope} backed by the session's {@link CurrentUser} (design 02-customer backend §4, §6). Registered as
 * the bean {@code customerScope} for {@code @PreAuthorize} expressions.
 */
@Component("customerScope")
class CustomerScopeImpl implements CustomerScope {

    @Override
    public boolean isCustomer() {
        return linkedCustomerId().isPresent();
    }

    @Override
    public long requireCustomerId() {
        return linkedCustomerId().orElseThrow(() -> new AccessDeniedException("Not a customer with a linked customer"));
    }

    private static Optional<Long> linkedCustomerId() {
        return CurrentUser.get()
                .filter(user -> user.userType() == UserType.CUSTOMER)
                .map(CurrentUser::customerId);
    }
}

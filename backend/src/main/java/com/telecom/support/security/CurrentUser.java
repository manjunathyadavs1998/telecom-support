package com.telecom.support.security;

import com.telecom.support.common.error.Errors.ForbiddenException;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Access to the authenticated {@link AppUser}. Services use this instead of ids supplied by the client. */
public final class CurrentUser {

    private CurrentUser() {}

    public static Optional<AppUser> get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUser user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }

    public static AppUser require() {
        return get().orElseThrow(() -> new ForbiddenException("Authentication required"));
    }

    /** The caller's own customer id; fails for staff or for customers not linked to an account. */
    public static long requireCustomerId() {
        AppUser user = require();
        if (!user.isCustomer() || user.customerId() == null) {
            throw new ForbiddenException("Only customers linked to an account can access this resource");
        }
        return user.customerId();
    }
}

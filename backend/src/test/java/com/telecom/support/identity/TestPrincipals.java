package com.telecom.support.identity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * Test-only reduced-authority principals (design 03-inbound backend §9 "reduced-authority principal"): a real test user
 * loaded like at login, minus some permissions, so an IT can prove a service-level permission check (e.g.
 * {@code case.note.internal}, {@code case.category.internal}) that every seeded role holding the endpoint permission
 * also grants. Use with {@code SecurityMockMvcRequestPostProcessors.authentication(...)}.
 */
public final class TestPrincipals {

    private TestPrincipals() {
    }

    /** {@code username} as loaded by {@link AppUserDetailsService}, without {@code removed} permissions. */
    public static Authentication without(AppUserDetailsService users, String username, String... removed) {
        CurrentUser full = users.loadUserByUsername(username);
        Set<String> permissions = new HashSet<>(full.permissions());
        permissions.removeAll(List.of(removed));
        CurrentUser reduced = new CurrentUser(full.id(), full.getUsername(), full.getPassword(), full.displayName(),
                full.userType(), full.customerId(), full.teamId(), full.isEnabled(), full.roles(), permissions);
        reduced.eraseCredentials();
        return new UsernamePasswordAuthenticationToken(reduced, null, reduced.getAuthorities());
    }
}

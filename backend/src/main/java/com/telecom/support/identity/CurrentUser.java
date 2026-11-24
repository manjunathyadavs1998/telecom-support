package com.telecom.support.identity;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The authenticated principal kept in the server session (design 01-foundation backend §6): user id, team and customer
 * id, roles, and permission codes as the only granted authorities (§3, BR-10). Services take a customer's account id
 * from {@link #customerId()}, never from the request.
 *
 * <p>Built by {@link AppUserDetailsService}. The password hash is erased after authentication, so it is never
 * serialised into the session. Role changes apply at the next login (§4.3).
 */
public final class CurrentUser implements UserDetails, CredentialsContainer, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public enum UserType { CUSTOMER, STAFF }

    private final long id;
    private final String username;
    private String passwordHash;
    private final String displayName;
    private final UserType userType;
    private final Long customerId;
    private final Long teamId;
    private final boolean active;
    private final Set<String> roles;
    private final Set<String> permissions;

    CurrentUser(long id, String username, String passwordHash, String displayName, UserType userType,
                Long customerId, Long teamId, boolean active, Set<String> roles, Set<String> permissions) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.userType = userType;
        this.customerId = customerId;
        this.teamId = teamId;
        this.active = active;
        this.roles = Set.copyOf(roles);
        this.permissions = Set.copyOf(permissions);
    }

    /** The authenticated user of the current request, if any. */
    public static Optional<CurrentUser> get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CurrentUser user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }

    /** The authenticated user; every protected endpoint has one, so absence is a programming error. */
    public static CurrentUser require() {
        return get().orElseThrow(() -> new IllegalStateException("No authenticated user in the security context"));
    }

    public long id() { return id; }
    public String displayName() { return displayName; }
    public UserType userType() { return userType; }
    public Long customerId() { return customerId; }
    public Long teamId() { return teamId; }
    public Set<String> roles() { return roles; }
    public Set<String> permissions() { return permissions; }

    public boolean has(String permission) {
        return permissions.contains(permission);
    }

    /** Authorities are exactly the permission codes (§6); roles are not authorities. */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return permissions.stream().sorted().<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    /** {@code users.status = ACTIVE}. */
    @Override
    public boolean isEnabled() {
        return active;
    }

    @Override
    public void eraseCredentials() {
        this.passwordHash = null;
    }

    @Override
    public String toString() {
        // no password hash (CLAUDE.md backend rule 9)
        return "CurrentUser[id=" + id + ", userType=" + userType + ", roles=" + roles + "]";
    }
}

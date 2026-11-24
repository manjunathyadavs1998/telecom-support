package com.telecom.support.security;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Authenticated principal stored in the server session. {@code customerId} is the only source of a customer's
 * account scope (BRD CUS-01.3) — never trust a customer id sent by the client.
 */
public final class AppUser implements UserDetails, CredentialsContainer, Serializable {

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
    private final Set<String> roles;
    private final Set<String> permissions;
    private final boolean enabled;
    private final Instant lockedUntil;

    public AppUser(long id, String username, String passwordHash, String displayName, UserType userType,
                   Long customerId, Long teamId, Set<String> roles, Set<String> permissions,
                   boolean enabled, Instant lockedUntil) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.userType = userType;
        this.customerId = customerId;
        this.teamId = teamId;
        this.roles = Set.copyOf(roles);
        this.permissions = Set.copyOf(permissions);
        this.enabled = enabled;
        this.lockedUntil = lockedUntil;
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

    public boolean isCustomer() {
        return userType == UserType.CUSTOMER;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        permissions.forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
        roles.forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
        return authorities;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonLocked() {
        return lockedUntil == null || lockedUntil.isBefore(Instant.now());
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /** Called by Spring Security after authentication so the hash is never serialised into the session. */
    @Override
    public void eraseCredentials() {
        this.passwordHash = null;
    }
}

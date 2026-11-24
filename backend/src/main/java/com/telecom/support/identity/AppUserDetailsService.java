package com.telecom.support.identity;

import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads a {@link CurrentUser} whose authorities are its permission codes (design 01-foundation backend §6, BR-10).
 * A DISABLED user loads with {@code isEnabled() == false}; the login flow (B8) turns that into the generic 401.
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public AppUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public CurrentUser loadUserByUsername(String username) {
        return users.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
    }
}

package com.telecom.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.support.security.AppUser;
import com.telecom.support.security.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** MockMvc integration tests authenticated as real seeded users (roles/permissions loaded from MySQL). */
@AutoConfigureMockMvc
public abstract class AbstractWebIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected UserRepository userRepository;

    /** Seeded usernames: customer1..4 aliases map to mobile numbers; staff use their employee id. */
    protected AppUser appUser(String username) {
        String resolved = switch (username) {
            case "customer1" -> "+919876543210";
            case "customer2" -> "+919876500001";
            case "customer3" -> "+919876500002";
            case "customer4" -> "+919876500003";
            default -> username;
        };
        return userRepository.findByUsername(resolved)
                .orElseThrow(() -> new IllegalArgumentException("No seeded user " + username));
    }

    protected RequestPostProcessor user(String username) {
        AppUser u = appUser(username);
        return authentication(UsernamePasswordAuthenticationToken.authenticated(u, null, u.getAuthorities()));
    }

    /** For service-level tests that call beans directly. */
    protected void runAs(String username) {
        AppUser u = appUser(username);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(u, null, u.getAuthorities()));
    }
}

package com.telecom.support.security;

import com.telecom.support.audit.AuditAction;
import com.telecom.support.audit.AuditEntry.Outcome;
import com.telecom.support.audit.AuditService;
import com.telecom.support.common.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    /** Same message for unknown user and wrong password, so usernames cannot be enumerated. */
    static final class BadCredentials extends ApiException {
        BadCredentials(String message) {
            super(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", message);
        }
    }

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contextRepository;
    private final UserRepository users;
    private final AuditService audit;
    private final Clock clock;
    private final int maxFailures;
    private final int lockMinutes;

    public AuthService(AuthenticationManager authenticationManager, SecurityContextRepository contextRepository,
                       UserRepository users, AuditService audit, Clock clock,
                       @Value("${app.security.max-failed-logins:5}") int maxFailures,
                       @Value("${app.security.lock-minutes:15}") int lockMinutes) {
        this.authenticationManager = authenticationManager;
        this.contextRepository = contextRepository;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
        this.maxFailures = maxFailures;
        this.lockMinutes = lockMinutes;
    }

    public AppUser login(String username, String password, HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(username, password));
        } catch (LockedException e) {
            audit.recordIndependently(AuditAction.LOGIN_FAILURE, Outcome.DENIED, "user", null, Map.of("reason", "locked"), username);
            throw new BadCredentials("Your account is temporarily locked after repeated failed attempts. Try again later.");
        } catch (AuthenticationException e) {
            users.loginFailed(username, clock.instant(), maxFailures, lockMinutes);
            audit.recordIndependently(AuditAction.LOGIN_FAILURE, Outcome.FAILURE, "user", null, Map.of("reason", "bad_credentials"), username);
            throw new BadCredentials("The mobile number / employee ID or password is incorrect.");
        }

        // Rotate the session id on login (session fixation protection) and persist the context in the session.
        request.getSession(true);
        request.changeSessionId();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);

        AppUser user = (AppUser) authentication.getPrincipal();
        users.loginSucceeded(user.id(), clock.instant());
        audit.recordIndependently(AuditAction.LOGIN_SUCCESS, Outcome.SUCCESS, "user", user.id(), null, null);
        return user;
    }
}

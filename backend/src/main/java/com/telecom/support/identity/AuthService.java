package com.telecom.support.identity;

import com.telecom.support.audit.AuditEvent.Outcome;
import com.telecom.support.audit.AuditService;
import com.telecom.support.identity.LoginRateLimiter.Throttled;
import com.telecom.support.identity.UserRepository.UserRow;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Credential check for {@code POST /api/v1/auth/login} and the logout audit (design 01-foundation backend §4, §4.1, §7;
 * NFR-SEC). Session handling (fixation, context, CSRF rotation) stays in {@link AuthController}.
 *
 * <ul>
 *   <li>L1/L2 of {@link LoginRateLimiter} run before the credential check; a 429 is logged at WARN, never audited.</li>
 *   <li>Constant time: an unknown or DISABLED user is still checked with BCrypt against a dummy hash of the same cost,
 *       and every failure is the same {@link BadCredentialsException}. The real reason goes only into the audit row.
 *       Only the {@code users} row (with the hash) is read before BCrypt; roles and permissions are loaded after a
 *       successful match, so known and unknown usernames run the same queries before the hash check.</li>
 *   <li>LOGIN_FAILURE / LOGIN_ANOMALY: null actor, masked username, written independently so they survive a rollback.
 *       LOGIN_SUCCESS is written with {@code last_login_at} in one transaction. There is no account lock.</li>
 * </ul>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
    static final String LOGIN_FAILURE = "LOGIN_FAILURE";
    static final String LOGIN_ANOMALY = "LOGIN_ANOMALY";
    static final String LOGOUT = "LOGOUT";
    static final String ENTITY_USER = "USER";

    /** Every failed credential check; maps to the one generic 401 body. */
    public static final class BadCredentialsException extends RuntimeException {
        BadCredentialsException() {
            super("Invalid username or password");
        }
    }

    /** L1 or L2 hit; maps to 429 {@code too-many-requests} with {@code Retry-After}. */
    public static final class LoginThrottledException extends RuntimeException {
        private final long retryAfterSeconds;

        LoginThrottledException(Throttled throttled) {
            super("Login throttled (" + throttled.limit() + ")");
            this.retryAfterSeconds = throttled.retryAfterSeconds();
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    private final UserRepository users;
    private final LoginRateLimiter limiter;
    private final AuditService audit;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    /** Same cost factor as stored hashes (BCrypt default 10); its plaintext is random and never known. */
    private final String dummyHash = encoder.encode(UUID.randomUUID().toString());

    public AuthService(UserRepository users, LoginRateLimiter limiter, AuditService audit, Clock clock,
                       PlatformTransactionManager transactionManager) {
        this.users = users;
        this.limiter = limiter;
        this.audit = audit;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * Checks the credentials of one login attempt from {@code clientIp}.
     *
     * @return the authenticated user, credentials already erased
     * @throws LoginThrottledException L1/L2 (checked first)
     * @throws BadCredentialsException unknown user, DISABLED user or bad password (indistinguishable)
     */
    public CurrentUser authenticate(String clientIp, String username, String password) {
        String name = username.trim();
        Optional<Throttled> throttled = limiter.acquire(clientIp, name);
        if (throttled.isPresent()) {
            log.warn("Login throttled ({}) for {} from {}", throttled.get().limit(), mask(name), clientIp);
            throw new LoginThrottledException(throttled.get());
        }

        Optional<UserRow> found = users.findRow(name);
        String reason;
        if (found.isEmpty()) {
            encoder.matches(password, dummyHash);
            reason = "unknown_user";
        } else if (!found.get().active()) {
            encoder.matches(password, dummyHash);
            reason = "disabled";
        } else if (!encoder.matches(password, found.get().passwordHash())) {
            reason = "bad_password";
        } else {
            reason = null;
        }

        if (reason != null) {
            fail(clientIp, name, reason);
        }
        CurrentUser user = users.withAuthorities(found.get()); // only after the hash matched
        user.eraseCredentials();
        limiter.recordSuccess(clientIp, name);
        tx.executeWithoutResult(status -> {
            users.updateLastLoginAt(user.id(), clock.instant());
            audit.record(LOGIN_SUCCESS, user.id(), user.getUsername(), ENTITY_USER, String.valueOf(user.id()), null);
        });
        return user;
    }

    /** Writes LOGOUT for the user whose session the controller is about to invalidate. */
    public void logout(CurrentUser user) {
        tx.executeWithoutResult(status ->
                audit.record(LOGOUT, user.id(), user.getUsername(), ENTITY_USER, String.valueOf(user.id()), null));
    }

    private void fail(String clientIp, String name, String reason) {
        String masked = mask(name);
        audit.recordIndependently(LOGIN_FAILURE, Outcome.FAILURE, null, null, null, null,
                Map.of("reason", reason, "username", masked));
        OptionalInt anomaly = limiter.recordFailure(clientIp, name);
        if (anomaly.isPresent()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("username", masked);
            details.put("failures", anomaly.getAsInt());
            details.put("windowMinutes", LoginRateLimiter.USER_WINDOW.toMinutes());
            log.warn("Login anomaly: {} failures in {} min for {}", anomaly.getAsInt(),
                    LoginRateLimiter.USER_WINDOW.toMinutes(), masked);
            audit.recordIndependently(LOGIN_ANOMALY, Outcome.FAILURE, null, null, null, null, details);
        }
        throw new BadCredentialsException();
    }

    /** §7: first 3 and last 2 characters kept, the rest {@code *}; 5 characters or fewer → {@code ***}. */
    static String mask(String username) {
        if (username == null || username.length() <= 5) {
            return "***";
        }
        return username.substring(0, 3) + "*".repeat(username.length() - 5)
                + username.substring(username.length() - 2);
    }
}

package com.telecom.support.identity;

import com.telecom.support.common.error.ProblemDetailFactory;
import com.telecom.support.common.error.ProblemDetailFactory.Problem;
import com.telecom.support.identity.AuthService.BadCredentialsException;
import com.telecom.support.identity.AuthService.LoginThrottledException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/auth} (design 01-foundation backend §4, §5; openapi.yaml). Login and logout are session + CSRF
 * operations: login changes the session id (fixation), stores the security context and the login time, and rotates the
 * {@code XSRF-TOKEN} cookie; logout writes LOGOUT, invalidates the session and rotates the cookie. {@code /me} and
 * {@code /logout} need a session (URL rule in {@link SecurityConfig}).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /** Shown for every failed credential check (unknown user, DISABLED, bad password). */
    static final String BAD_CREDENTIALS_DETAIL = "Invalid username or password.";

    public record CsrfTokenResponse(String headerName, String token) {}

    public record LoginRequest(@NotBlank @Size(max = 64) String username,
                               @NotBlank @Size(max = 128) String password) {
        @Override
        public String toString() {
            return "LoginRequest[username=" + AuthService.mask(username) + "]"; // never the password
        }
    }

    public record Me(long id, String username, String displayName, String userType, Long teamId, Long customerId,
                     List<String> roles, List<String> permissions) {

        static Me of(CurrentUser u) {
            return new Me(u.id(), u.getUsername(), u.displayName(), u.userType().name(), u.teamId(), u.customerId(),
                    u.roles().stream().sorted().toList(), u.permissions().stream().sorted().toList());
        }
    }

    private final AuthService auth;
    private final CsrfTokenRepository csrfTokens;
    private final SecurityContextRepository contexts;
    private final ProblemDetailFactory problems;
    private final Clock clock;

    public AuthController(AuthService auth, CsrfTokenRepository csrfTokens, SecurityContextRepository contexts,
                          ProblemDetailFactory problems, Clock clock) {
        this.auth = auth;
        this.csrfTokens = csrfTokens;
        this.contexts = contexts;
        this.problems = problems;
        this.clock = clock;
    }

    /** Bootstrap only: the body token equals the {@code XSRF-TOKEN} cookie value (§4.2). */
    @GetMapping("/csrf")
    public CsrfTokenResponse csrf(CsrfToken token) {
        return new CsrfTokenResponse(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/login")
    public Me login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        CurrentUser user = auth.authenticate(clientIp(request), body.username(), body.password());

        if (request.getSession(false) != null) {
            request.changeSessionId(); // session fixation: an existing session gets a new id
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(SessionPolicyFilter.LOGIN_AT, clock.millis());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);

        rotateCsrfToken(request, response);
        return Me.of(user);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        auth.logout(CurrentUser.require());
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        rotateCsrfToken(request, response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public Me me() {
        return Me.of(CurrentUser.require());
    }

    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<ProblemDetail> badCredentials(HttpServletRequest request) {
        ProblemDetail pd = problems.create(Problem.UNAUTHENTICATED, request.getRequestURI());
        pd.setDetail(BAD_CREDENTIALS_DETAIL);
        return ResponseEntity.status(pd.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }

    @ExceptionHandler(LoginThrottledException.class)
    ResponseEntity<ProblemDetail> throttled(LoginThrottledException ex, HttpServletRequest request) {
        ProblemDetail pd = problems.create(Problem.TOO_MANY_REQUESTS, request.getRequestURI());
        return ResponseEntity.status(pd.getStatus())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }

    /** Writes a new {@code XSRF-TOKEN} cookie, replacing the one the request carried (§4.2). */
    private void rotateCsrfToken(HttpServletRequest request, HttpServletResponse response) {
        CsrfToken token = csrfTokens.generateToken(request);
        csrfTokens.saveToken(token, request, response);
        request.setAttribute(CsrfToken.class.getName(), token);
        request.setAttribute(token.getParameterName(), token);
    }

    /** Seam for the limiter's client IP: the remote address until B8b swaps in {@code ClientIp.resolve} (§4.4). */
    static String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}

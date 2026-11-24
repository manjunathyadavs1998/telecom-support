package com.telecom.support.security;

import com.telecom.support.common.error.Errors.TooManyRequestsException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
public class AuthController {

    public record LoginRequest(
            @NotBlank(message = "Enter your mobile number or employee ID") @Size(max = 64) String username,
            @NotBlank(message = "Enter your password") @Size(max = 128) String password) {}

    public record MeResponse(long id, String username, String displayName, String userType,
                             @Schema(nullable = true) Long customerId, @Schema(nullable = true) Long teamId,
                             List<String> roles, List<String> permissions) {

        static MeResponse of(AppUser u) {
            return new MeResponse(u.id(), u.getUsername(), u.displayName(), u.userType().name(), u.customerId(),
                    u.teamId(), u.roles().stream().sorted().toList(), u.permissions().stream().sorted().toList());
        }
    }

    public record CsrfResponse(String headerName, String token) {}

    private final AuthService authService;
    private final LoginRateLimiter rateLimiter;

    public AuthController(AuthService authService, LoginRateLimiter rateLimiter) {
        this.authService = authService;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/csrf")
    @Operation(summary = "Issue the CSRF cookie/token for the SPA")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/login")
    @Operation(summary = "Sign in and start a server session")
    public MeResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        if (!rateLimiter.tryAcquire(request.getRemoteAddr(), body.username())) {
            throw new TooManyRequestsException("Too many sign-in attempts. Please wait a minute and try again.");
        }
        return MeResponse.of(authService.login(body.username().trim(), body.password(), request, response));
    }

    @GetMapping("/me")
    @Operation(summary = "Current user with roles and permissions (drives role-based navigation)")
    public ResponseEntity<MeResponse> me(@AuthenticationPrincipal AppUser user) {
        return ResponseEntity.ok(MeResponse.of(user));
    }
}

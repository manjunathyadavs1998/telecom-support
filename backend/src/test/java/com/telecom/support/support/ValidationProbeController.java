package com.telecom.support.support;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only probe for {@code ErrorModelIT} (01 backend §8). {@link TestComponent} keeps it out of component scanning;
 * ITs pull it in with {@code @Import}, so it can never ship.
 */
@TestComponent
@RestController
@RequestMapping("/api/v1/test-probe")
public class ValidationProbeController {

    public static final String SECRET = "SELECT * FROM users -- internal detail";

    public record ProbeRequest(@NotBlank String name, @NotBlank @Email String email) {}

    @GetMapping("/ok")
    Map<String, String> ok() {
        return Map.of("status", "ok");
    }

    @PostMapping(path = "/validate", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, String> validate(@Valid @RequestBody ProbeRequest body) {
        return Map.of("name", body.name());
    }

    @GetMapping("/boom")
    Map<String, String> boom() {
        throw new IllegalStateException(SECRET);
    }

    /** Stands in for a {@code @PreAuthorize} denial raised inside the controller call. */
    @GetMapping("/denied")
    Map<String, String> denied() {
        throw new AccessDeniedException("probe");
    }
}

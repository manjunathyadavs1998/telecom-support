package com.telecom.support.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.AccessDeniedException;

/**
 * Port called exactly once per 403: by the security chain's {@code AccessDeniedHandler} (URL-level and CSRF) and by
 * {@code GlobalExceptionHandler} ({@code @PreAuthorize}). The {@code audit} module implements it to write the sampled
 * {@code ACCESS_DENIED} row (01 backend §4.4, §7), so {@code common} never depends on a feature module (ADR 0001).
 */
public interface AccessDeniedListener {

    void onAccessDenied(HttpServletRequest request, AccessDeniedException denial);
}

package com.telecom.support.support;

import com.telecom.support.common.error.ApiExceptions.BusinessRuleException;
import com.telecom.support.common.error.ApiExceptions.ConflictException;
import com.telecom.support.common.error.ApiExceptions.IdempotencyMismatchException;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only probe that raises the service-level 00-overview §4 rows (409, 422 x2) the way a service would. Imported by
 * {@code ProblemShapeIT} only, so it can never ship. The messages carry internal text that must not reach the client.
 */
@TestComponent
@RestController
@RequestMapping("/api/v1/test-probe-errors")
public class ApiErrorProbeController {

    public static final String INTERNAL_TEXT = "internal-row-17 secret";
    public static final String USER_SAFE_DETAIL = "This account is not eligible for reactivation.";

    @GetMapping("/conflict")
    String conflict() {
        throw new ConflictException(INTERNAL_TEXT);
    }

    @GetMapping("/business-rule")
    String businessRule() {
        throw new BusinessRuleException(INTERNAL_TEXT);
    }

    @GetMapping("/business-rule-detail")
    String businessRuleWithDetail() {
        throw new BusinessRuleException(INTERNAL_TEXT, USER_SAFE_DETAIL);
    }

    @GetMapping("/idempotency-mismatch")
    String idempotencyMismatch() {
        throw new IdempotencyMismatchException(INTERNAL_TEXT);
    }
}

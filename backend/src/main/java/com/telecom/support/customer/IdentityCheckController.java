package com.telecom.support.customer;

import com.telecom.support.common.error.ProblemDetailFactory;
import com.telecom.support.common.error.ProblemDetailFactory.Problem;
import com.telecom.support.customer.IdentityCheckService.IdentityCheck;
import com.telecom.support.customer.IdentityCheckService.IdentityCheckFailedException;
import com.telecom.support.customer.IdentityCheckService.Method;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/agent/customers/{customerId}/identity-checks} — I2, I3, I4 (design 03-inbound backend §4, §5, R2–R4;
 * openapi.yaml). Every handler needs {@code customer.lookup} and {@code customer.verify} (inbound agents only). These
 * routes are the only {@code /agent/customers/{customerId}/**} routes without a verified check (R5).
 */
@RestController
@RequestMapping("/api/v1/agent/customers/{customerId}/identity-checks")
public class IdentityCheckController {

    /** The {@code IdentityCheckStart} schema; KBA needs both answers (R4). Answers are never stored. */
    @KbaAnswersRequired
    public record IdentityCheckStart(@NotNull @Size(min = 16, max = 64) String lookupToken, @NotNull Method method,
                                     @Size(min = 3, max = 12) String postalCode,
                                     @Size(min = 3, max = 254) String registeredContact) {

        /** Never print the token or the answers (logs, debuggers). */
        @Override
        public String toString() {
            return "IdentityCheckStart[method=" + method + ", lookupToken=***, answers=***]";
        }
    }

    /** The {@code IdentityCheckConfirm} schema. */
    public record IdentityCheckConfirm(
            @NotNull @Pattern(regexp = "^\\d{6}$", message = "must be 6 digits") String code) {

        /** Never print the code (logs, debuggers). */
        @Override
        public String toString() {
            return "IdentityCheckConfirm[code=******]";
        }
    }

    private final IdentityCheckService checks;
    private final ProblemDetailFactory problems;

    public IdentityCheckController(IdentityCheckService checks, ProblemDetailFactory problems) {
        this.checks = checks;
        this.problems = problems;
    }

    /** I2 (R2–R4). */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CUSTOMER_LOOKUP)"
            + " and hasAuthority(T(com.telecom.support.identity.Permissions).CUSTOMER_VERIFY)")
    public ResponseEntity<IdentityCheck> start(@PathVariable long customerId,
                                               @Valid @RequestBody IdentityCheckStart body) {
        IdentityCheck check = body.method() == Method.OTP
                ? checks.startOtp(customerId, body.lookupToken())
                : checks.startKba(customerId, body.lookupToken(), body.postalCode(), body.registeredContact());
        return ResponseEntity.status(HttpStatus.CREATED).body(check);
    }

    /** I3 (R3, 02 §3.2). */
    @PostMapping(path = "/{checkId}/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CUSTOMER_LOOKUP)"
            + " and hasAuthority(T(com.telecom.support.identity.Permissions).CUSTOMER_VERIFY)")
    public IdentityCheck confirm(@PathVariable long customerId, @PathVariable long checkId,
                                 @Valid @RequestBody IdentityCheckConfirm body) {
        return checks.confirm(customerId, checkId, body.code());
    }

    /** I4. */
    @GetMapping("/current")
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CUSTOMER_LOOKUP)"
            + " and hasAuthority(T(com.telecom.support.identity.Permissions).CUSTOMER_VERIFY)")
    public IdentityCheck current(@PathVariable long customerId) {
        return checks.current(customerId);
    }

    /** 422 {@code VerificationProblem}: business-rule plus {@code reason} and, when known, {@code attemptsRemaining}. */
    @ExceptionHandler(IdentityCheckFailedException.class)
    ResponseEntity<ProblemDetail> checkFailed(IdentityCheckFailedException ex, HttpServletRequest request) {
        ProblemDetail pd = problems.create(Problem.BUSINESS_RULE, request.getRequestURI());
        ex.userDetail().ifPresent(pd::setDetail);
        pd.setProperty("reason", ex.reason().value());
        ex.attemptsRemaining().ifPresent(n -> pd.setProperty("attemptsRemaining", n));
        return ResponseEntity.status(pd.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }

    /** R4: KBA needs {@code postalCode} and {@code registeredContact} (→ 400 on the missing field). */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = KbaAnswersRequiredValidator.class)
    public @interface KbaAnswersRequired {
        String message() default "must not be null for KBA";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    public static final class KbaAnswersRequiredValidator
            implements ConstraintValidator<KbaAnswersRequired, IdentityCheckStart> {

        @Override
        public boolean isValid(IdentityCheckStart start, ConstraintValidatorContext context) {
            if (start == null || start.method() != Method.KBA) {
                return true;
            }
            boolean valid = true;
            context.disableDefaultConstraintViolation();
            if (start.postalCode() == null) {
                context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                        .addPropertyNode("postalCode").addConstraintViolation();
                valid = false;
            }
            if (start.registeredContact() == null) {
                context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                        .addPropertyNode("registeredContact").addConstraintViolation();
                valid = false;
            }
            return valid;
        }
    }
}

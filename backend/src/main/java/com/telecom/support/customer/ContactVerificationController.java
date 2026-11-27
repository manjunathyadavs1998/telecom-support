package com.telecom.support.customer;

import com.telecom.support.common.error.ProblemDetailFactory;
import com.telecom.support.common.error.ProblemDetailFactory.Problem;
import com.telecom.support.customer.ContactVerificationService.ContactVerification;
import com.telecom.support.customer.ContactVerificationService.FailureReason;
import com.telecom.support.customer.ContactVerificationService.VerificationFailedException;
import com.telecom.support.customer.CustomerService.Profile;
import com.telecom.support.customer.VerificationCodeSender.Channel;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/customers/me/contact-verifications} — E3, E4 (design 02-customer backend §4, §5, R3, §3.2;
 * openapi.yaml). Both need {@code customer.profile.update} and a linked customer principal; the customer is never taken
 * from the request.
 */
@RestController
@RequestMapping("/api/v1/customers/me/contact-verifications")
public class ContactVerificationController {

    static final java.util.regex.Pattern EMAIL = java.util.regex.Pattern.compile("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$");
    static final java.util.regex.Pattern MSISDN = java.util.regex.Pattern.compile("^\\+[1-9]\\d{7,14}$");

    /** The {@code ContactVerificationStart} schema; {@code value} must suit {@code channel} (R3). */
    @ValueMatchesChannel
    public record ContactVerificationStart(@NotNull Channel channel, @NotNull @Size(min = 3, max = 254) String value) {}

    /** The {@code ContactVerificationConfirm} schema. */
    public record ContactVerificationConfirm(
            @NotNull @Pattern(regexp = "^\\d{6}$", message = "must be 6 digits") String code) {

        /** Never print the code (logs, debuggers). */
        @Override
        public String toString() {
            return "ContactVerificationConfirm[code=******]";
        }
    }

    private final ContactVerificationService verifications;
    private final ProblemDetailFactory problems;

    public ContactVerificationController(ContactVerificationService verifications, ProblemDetailFactory problems) {
        this.verifications = verifications;
        this.problems = problems;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CUSTOMER_PROFILE_UPDATE)"
            + " and @customerScope.isCustomer()")
    public ResponseEntity<ContactVerification> start(@Valid @RequestBody ContactVerificationStart body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(verifications.start(body.channel(), body.value()));
    }

    @PostMapping(path = "/{verificationId}/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CUSTOMER_PROFILE_UPDATE)"
            + " and @customerScope.isCustomer()")
    public Profile confirm(@PathVariable long verificationId, @Valid @RequestBody ContactVerificationConfirm body) {
        return verifications.confirm(verificationId, body.code());
    }

    /** 422 {@code VerificationProblem}: business-rule plus {@code attemptsRemaining} (not for an expired code). */
    @ExceptionHandler(VerificationFailedException.class)
    ResponseEntity<ProblemDetail> verificationFailed(VerificationFailedException ex, HttpServletRequest request) {
        ProblemDetail pd = problems.create(Problem.BUSINESS_RULE, request.getRequestURI());
        ex.userDetail().ifPresent(pd::setDetail);
        if (ex.reason() != FailureReason.EXPIRED) {
            pd.setProperty("attemptsRemaining", ex.attemptsRemaining());
        }
        return ResponseEntity.status(pd.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }

    /** R3 format of {@code value}: an email address for EMAIL, an E.164 number for SECONDARY_MSISDN (→ 400 on "value"). */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = ValueMatchesChannelValidator.class)
    public @interface ValueMatchesChannel {
        String message() default "must be an email address for EMAIL or an E.164 number for SECONDARY_MSISDN";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    public static final class ValueMatchesChannelValidator
            implements ConstraintValidator<ValueMatchesChannel, ContactVerificationStart> {

        @Override
        public boolean isValid(ContactVerificationStart start, ConstraintValidatorContext context) {
            if (start == null || start.channel() == null || start.value() == null) {
                return true; // @NotNull reports these
            }
            if (start.channel() == Channel.PRIMARY_MSISDN) {
                // 03's identity check channel (B6): not a contact the customer can verify, 400 on "channel"
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate("must be EMAIL or SECONDARY_MSISDN")
                        .addPropertyNode("channel")
                        .addConstraintViolation();
                return false;
            }
            String value = start.value().trim();
            boolean valid = start.channel() == Channel.EMAIL ? EMAIL.matcher(value).matches()
                    : MSISDN.matcher(value).matches();
            if (!valid) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                        .addPropertyNode("value")
                        .addConstraintViolation();
            }
            return valid;
        }
    }
}

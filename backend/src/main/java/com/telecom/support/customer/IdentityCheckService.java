package com.telecom.support.customer;

import com.telecom.support.audit.AuditEvent.Outcome;
import com.telecom.support.audit.AuditService;
import com.telecom.support.common.error.ApiExceptions.BusinessRuleException;
import com.telecom.support.common.error.ApiExceptions.DependencyUnavailableException;
import com.telecom.support.common.error.ApiExceptions.NotFoundException;
import com.telecom.support.common.error.ApiExceptions.TooManyRequestsException;
import com.telecom.support.common.security.ActorProvider;
import com.telecom.support.common.security.ActorProvider.Actor;
import com.telecom.support.customer.IdentityCheckRepository.CheckRow;
import com.telecom.support.customer.IdentityCheckRepository.CustomerFacts;
import com.telecom.support.customer.IdentityCheckRepository.Window;
import com.telecom.support.customer.VerificationCodeSender.Channel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Agent identity check I2–I4 (design 03-inbound backend R2–R4, §7, §8; INB-01.1, INB-01.2, §14 #2, NFR-SEC).
 *
 * <p><b>Start (I2)</b> needs this agent's unexpired lookup token for the customer (R2); an unknown customer id and a
 * missing, expired or foreign token give the same 404.
 * <ul>
 *   <li><b>OTP</b> (R3): one transaction locks the customer row, refuses when the customer already had
 *       {@code app.customer.otp.starts-per-hour} OTP starts in the last hour (429, all agents), supersedes this agent's
 *       PENDING/VERIFIED check, inserts PENDING (HMAC of the code only) and audits; after the commit the code goes to
 *       the primary MSISDN ({@link Channel#PRIMARY_MSISDN}); a failed send marks the check EXPIRED → 503.</li>
 *   <li><b>KBA</b> (R4): tx A locks the customer row, refuses at {@code kba-customer-per-hour} (customer) or
 *       {@code kba-agent-per-hour} (agent) CHECKING/FAILED rows in the last hour (429), inserts CHECKING and commits;
 *       only then are the HMACs of the normalised answers compared in constant time; tx B moves CHECKING → VERIFIED or
 *       FAILED by a guarded update. A crash between them leaves CHECKING, which counts as a failure.</li>
 * </ul>
 *
 * <p><b>Confirm (I3)</b> is 02 §3.2: the attempt is counted by a guarded update committed in its own transaction before
 * the constant-time compare; LOCKED and VERIFIED are guarded updates; {@code max-attempts} attempts. A check of another
 * agent or customer is 404. VERIFIED is valid for {@code identity-check.validity} (30 min) for this agent and customer.
 *
 * <p>The code and the KBA answers are never stored, logged, audited, put in an exception message or returned.
 */
@Service
@EnableConfigurationProperties({InboundProperties.class, OtpProperties.class})
public class IdentityCheckService {

    static final String STARTED = "IDENTITY_CHECK_STARTED";
    static final String PASSED = "IDENTITY_CHECK_PASSED";
    static final String FAILED = "IDENTITY_CHECK_FAILED";
    static final Duration RATE_WINDOW = Duration.ofHours(1);

    private static final Logger log = LoggerFactory.getLogger(IdentityCheckService.class);

    public enum Method { OTP, KBA }

    /** The {@code IdentityCheck} schema of openapi.yaml. */
    public record IdentityCheck(long id, Method method, String status, String maskedDestination, Instant expiresAt,
                                Instant validUntil) {}

    /** Why a check was refused (422 {@code VerificationProblem.reason}); also the audit {@code reason}. */
    public enum FailureReason {
        WRONG_CODE("wrong_code", "The code is incorrect."),
        EXPIRED("expired", "This code has expired or is no longer valid. Request a new code."),
        LOCKED("locked", "Too many wrong attempts. Request a new code."),
        KBA_MISMATCH("kba_mismatch", "The answers do not match the customer's records."),
        KBA_UNAVAILABLE("kba_unavailable", "This customer has no verified contact, so questions cannot be used. Use a code."),
        NO_OTP_DESTINATION("no_otp_destination", "This customer has no primary mobile number for a code. Use questions.");

        private final String value;
        private final String userDetail;

        FailureReason(String value, String userDetail) {
            this.value = value;
            this.userDetail = userDetail;
        }

        public String value() {
            return value;
        }
    }

    /** 422 {@code business-rule} for a refused check; {@code attemptsRemaining} (nullable) goes into the problem body. */
    public static class IdentityCheckFailedException extends BusinessRuleException {
        private final transient FailureReason reason;
        private final Integer attemptsRemaining;

        IdentityCheckFailedException(String message, FailureReason reason, Integer attemptsRemaining) {
            super(message + ": " + reason.value(), reason.userDetail);
            this.reason = reason;
            this.attemptsRemaining = attemptsRemaining;
        }

        public FailureReason reason() {
            return reason;
        }

        public Optional<Integer> attemptsRemaining() {
            return Optional.ofNullable(attemptsRemaining);
        }
    }

    private final IdentityCheckRepository checks;
    private final CustomerRepository customers;
    private final CustomerLookupService lookups;
    private final VerificationCodeSender sender;
    private final OtpCodec codec;
    private final AuditService audit;
    private final ActorProvider actors;
    private final OtpProperties otp;
    private final InboundProperties inbound;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate newTx;

    IdentityCheckService(IdentityCheckRepository checks, CustomerRepository customers, CustomerLookupService lookups,
                         VerificationCodeSender sender, OtpCodec codec, AuditService audit, ActorProvider actors,
                         OtpProperties otp, InboundProperties inbound, Clock clock,
                         PlatformTransactionManager transactions) {
        this.checks = checks;
        this.customers = customers;
        this.lookups = lookups;
        this.sender = sender;
        this.codec = codec;
        this.audit = audit;
        this.actors = actors;
        this.otp = otp;
        this.inbound = inbound;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
        this.newTx = new TransactionTemplate(transactions);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------ I2

    /** I2 OTP start (R2, R3). */
    public IdentityCheck startOtp(long customerId, String lookupToken) {
        Actor agent = actors.currentActor().orElseThrow();
        requireLookup(agent, customerId, lookupToken);
        String code = codec.newCode();
        String codeHmac = codec.hmac(code);

        record Started(IdentityCheck check, String destination) {}
        Started started = auditedAfterRollback(() -> tx.execute(status -> {
            lockCustomer(customerId);
            CustomerFacts facts = facts(customerId);
            if (facts.primaryMsisdn() == null) {
                throw new IdentityCheckFailedException("No OTP destination for customer " + customerId,
                        FailureReason.NO_OTP_DESTINATION, null);
            }
            Instant now = clock.instant();
            Window starts = checks.otpStartsSince(customerId, now.minus(RATE_WINDOW));
            if (starts.count() >= otp.startsPerHour()) {
                throw rateLimited(agent, facts, Method.OTP, starts, now, "OTP starts per hour exceeded");
            }
            checks.supersedeOpen(agent.id(), customerId, -1, now);
            Instant expiresAt = now.plus(otp.ttl());
            long id = checks.insertOtp(agent.id(), customerId, codeHmac, expiresAt, now);
            audit.record(STARTED, agent.id(), agent.username(), CustomerLookupService.ENTITY_CUSTOMER,
                    facts.customerRef(), details(Method.OTP, id, null));
            return new Started(new IdentityCheck(id, Method.OTP, IdentityCheckRepository.PENDING,
                    CustomerLookupService.maskMsisdn(facts.primaryMsisdn()), expiresAt, null), facts.primaryMsisdn());
        }));

        try {
            sender.send(Channel.PRIMARY_MSISDN, started.destination(), code);
        } catch (RuntimeException e) { // DeliveryException, or any other failure of the port
            long id = started.check().id();
            newTx.executeWithoutResult(status -> checks.transition(id, IdentityCheckRepository.PENDING,
                    IdentityCheckRepository.EXPIRED, clock.instant()));
            log.warn("Identity check code for check {} could not be delivered: {}", id, e.getClass().getSimpleName());
            throw new DependencyUnavailableException("Verification code sender failed for identity check " + id);
        }
        return started.check();
    }

    /** I2 KBA (R2, R4): {@code postalCode} and {@code registeredContact} are already present and size-checked. */
    public IdentityCheck startKba(long customerId, String lookupToken, String postalCode, String registeredContact) {
        Actor agent = actors.currentActor().orElseThrow();
        requireLookup(agent, customerId, lookupToken);
        CustomerFacts facts = facts(customerId);
        if (facts.postalCode() == null
                || (facts.verifiedEmail() == null && facts.verifiedSecondaryMsisdn() == null)) {
            throw new IdentityCheckFailedException("KBA unavailable for customer " + customerId,
                    FailureReason.KBA_UNAVAILABLE, null);
        }

        // tx A: the attempt is committed (and counts) before anything is compared
        long[] attempt = auditedAfterRollback(() -> newTx.execute(status -> {
            lockCustomer(customerId);
            Instant now = clock.instant();
            Window ofCustomer = checks.kbaAttemptsOfCustomerSince(customerId, now.minus(RATE_WINDOW));
            if (ofCustomer.count() >= inbound.kbaCustomerPerHour()) {
                throw rateLimited(agent, facts, Method.KBA, ofCustomer, now, "KBA attempts per customer exceeded");
            }
            Window ofAgent = checks.kbaAttemptsOfAgentSince(agent.id(), now.minus(RATE_WINDOW));
            if (ofAgent.count() >= inbound.kbaAgentPerHour()) {
                throw rateLimited(agent, facts, Method.KBA, ofAgent, now, "KBA attempts per agent exceeded");
            }
            return new long[] {checks.insertKbaChecking(agent.id(), customerId, now), ofCustomer.count() + 1L};
        }));
        long id = attempt[0];
        int remaining = (int) Math.max(0, inbound.kbaCustomerPerHour() - attempt[1]);

        boolean match = kbaMatches(facts, postalCode, registeredContact);

        // tx B: guarded verdict
        if (match) {
            Instant validUntil = tx.execute(status -> {
                Instant now = clock.instant();
                Instant until = now.plus(validity());
                if (checks.markVerified(id, IdentityCheckRepository.CHECKING, until, now) != 1) {
                    return null;
                }
                checks.supersedeOpen(agent.id(), customerId, id, now);
                audit.record(PASSED, agent.id(), agent.username(), CustomerLookupService.ENTITY_CUSTOMER,
                        facts.customerRef(), details(Method.KBA, id, until));
                return until;
            });
            if (validUntil != null) {
                return new IdentityCheck(id, Method.KBA, IdentityCheckRepository.VERIFIED, null, null, validUntil);
            }
        } else {
            newTx.executeWithoutResult(status -> checks.transition(id, IdentityCheckRepository.CHECKING,
                    IdentityCheckRepository.FAILED, clock.instant()));
        }
        throw refused(agent, facts.customerRef(), Method.KBA, id, FailureReason.KBA_MISMATCH, remaining);
    }

    // ------------------------------------------------------------------ I3

    /** I3: confirms {@code code} for this agent's OTP check {@code checkId} of the customer (02 §3.2). */
    public IdentityCheck confirm(long customerId, long checkId, String code) {
        Actor agent = actors.currentActor().orElseThrow();
        int max = otp.maxAttempts();

        // step 1: committed before any comparison; the row read under our own row lock gives our attempt number
        Optional<CheckRow> counted = newTx.execute(status ->
                checks.countOtpAttempt(checkId, agent.id(), customerId, max, clock.instant()) == 1
                        ? checks.find(checkId)
                        : Optional.<CheckRow>empty());
        if (counted.isEmpty()) {
            CheckRow row = checks.find(checkId)
                    .filter(r -> r.agentUserId() == agent.id() && r.customerId() == customerId
                            && IdentityCheckRepository.OTP.equals(r.method()))
                    .orElseThrow(() -> new NotFoundException("Identity check " + checkId + " not found for agent "
                            + agent.id() + " and customer " + customerId));
            boolean locked = IdentityCheckRepository.LOCKED.equals(row.status())
                    || (IdentityCheckRepository.PENDING.equals(row.status()) && row.attempts() >= max);
            throw locked
                    ? refused(agent, customerRef(customerId), Method.OTP, checkId, FailureReason.LOCKED, 0)
                    : refused(agent, customerRef(customerId), Method.OTP, checkId, FailureReason.EXPIRED, null);
        }
        CheckRow row = counted.get();
        int remaining = Math.max(0, max - row.attempts());

        // step 2: constant time, over the HMACs only
        if (!codec.matches(code, row.codeHmac())) {
            // step 3
            if (row.attempts() >= max) {
                newTx.executeWithoutResult(status -> checks.transition(checkId, IdentityCheckRepository.PENDING,
                        IdentityCheckRepository.LOCKED, clock.instant()));
            }
            throw refused(agent, customerRef(customerId), Method.OTP, checkId, FailureReason.WRONG_CODE, remaining);
        }

        // step 4: only the single PENDING → VERIFIED winner supersedes and audits
        CustomerFacts facts = facts(customerId);
        Instant validUntil = tx.execute(status -> {
            Instant now = clock.instant();
            Instant until = now.plus(validity());
            if (checks.markVerified(checkId, IdentityCheckRepository.PENDING, until, now) != 1) {
                return null;
            }
            checks.supersedeOpen(agent.id(), customerId, checkId, now);
            audit.record(PASSED, agent.id(), agent.username(), CustomerLookupService.ENTITY_CUSTOMER,
                    facts.customerRef(), details(Method.OTP, checkId, until));
            return until;
        });
        if (validUntil == null) {
            throw refused(agent, facts.customerRef(), Method.OTP, checkId, FailureReason.EXPIRED, null);
        }
        return new IdentityCheck(checkId, Method.OTP, IdentityCheckRepository.VERIFIED,
                facts.primaryMsisdn() == null ? null : CustomerLookupService.maskMsisdn(facts.primaryMsisdn()), null,
                validUntil);
    }

    // ------------------------------------------------------------------ I4

    /** I4: this agent's valid VERIFIED check for the customer; 404 when none. */
    public IdentityCheck current(long customerId) {
        Actor agent = actors.currentActor().orElseThrow();
        CheckRow row = checks.findCurrent(agent.id(), customerId, clock.instant())
                .orElseThrow(() -> new NotFoundException("No valid identity check of agent " + agent.id()
                        + " for customer " + customerId));
        return new IdentityCheck(row.id(), Method.valueOf(row.method()), row.status(), null, null, row.validUntil());
    }

    // ------------------------------------------------------------------ helpers

    /** R2: unknown customer and missing/expired/foreign token are the same 404. */
    private void requireLookup(Actor agent, long customerId, String lookupToken) {
        if (lookupToken == null || !lookups.isValidToken(lookupToken, agent.id(), customerId)) {
            throw new NotFoundException("No valid lookup of agent " + agent.id() + " for customer " + customerId);
        }
    }

    private void lockCustomer(long customerId) {
        if (!customers.lockForUpdate(customerId)) {
            throw new NotFoundException("Customer " + customerId + " not found");
        }
    }

    private CustomerFacts facts(long customerId) {
        return checks.findFacts(customerId)
                .orElseThrow(() -> new NotFoundException("Customer " + customerId + " not found"));
    }

    private String customerRef(long customerId) {
        return checks.findFacts(customerId).map(CustomerFacts::customerRef).orElse(null);
    }

    private Duration validity() {
        return inbound.identityCheck().validity();
    }

    /**
     * R4: postal code and registered contact against the billing postal code and the verified email or verified secondary
     * MSISDN, each normalised. Every comparison runs (no short circuit), in constant time over HMACs.
     */
    private boolean kbaMatches(CustomerFacts facts, String postalCode, String registeredContact) {
        byte[] given = kbaHmac(normalisePostal(postalCode), normaliseContact(registeredContact));
        String expectedPostal = normalisePostal(facts.postalCode());
        boolean hasEmail = facts.verifiedEmail() != null;
        boolean hasSecondary = facts.verifiedSecondaryMsisdn() != null;
        boolean email = MessageDigest.isEqual(given,
                kbaHmac(expectedPostal, hasEmail ? normaliseContact(facts.verifiedEmail()) : ""));
        boolean secondary = MessageDigest.isEqual(given,
                kbaHmac(expectedPostal, hasSecondary ? normaliseContact(facts.verifiedSecondaryMsisdn()) : ""));
        return (hasEmail & email) | (hasSecondary & secondary);
    }

    private byte[] kbaHmac(String postal, String contact) {
        return codec.hmac(postal + '\u0000' + contact).getBytes(StandardCharsets.US_ASCII);
    }

    static String normalisePostal(String value) {
        return value.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    /** An email is trimmed and lower-cased; a number keeps only {@code +} and digits. */
    static String normaliseContact(String value) {
        String v = value.trim();
        return v.indexOf('@') >= 0 ? v.toLowerCase(Locale.ROOT) : v.replaceAll("[^+0-9]", "");
    }

    private static Map<String, Object> details(Method method, long checkId, Instant validUntil) {
        Map<String, Object> details = new HashMap<>();
        details.put("method", method.name());
        details.put("checkId", checkId);
        details.put("validUntil", validUntil == null ? null : validUntil.toString());
        return details;
    }

    /**
     * A 429 decided under the customer row lock. It is thrown out of the transaction (rolling it back and releasing
     * the lock) and audited only then by {@link #auditedAfterRollback}, so the audit's own pool connection is never
     * taken while the lock is held (review C4).
     */
    private static final class RateLimited extends RuntimeException {
        private final transient Actor agent;
        private final String customerRef;
        private final Method method;
        private final TooManyRequestsException problem;

        RateLimited(Actor agent, String customerRef, Method method, TooManyRequestsException problem) {
            super(problem.getMessage(), null, false, false);
            this.agent = agent;
            this.customerRef = customerRef;
            this.method = method;
            this.problem = problem;
        }
    }

    /** Runs {@code body} (a transaction); a {@link RateLimited} is audited after the rollback and rethrown as 429. */
    private <T> T auditedAfterRollback(Supplier<T> body) {
        try {
            return body.get();
        } catch (RateLimited limited) {
            Map<String, Object> details = new HashMap<>();
            details.put("method", limited.method.name());
            details.put("reason", "rate_limited");
            details.put("attemptsRemaining", 0);
            audit.recordIndependently(FAILED, Outcome.FAILURE, limited.agent.id(), limited.agent.username(),
                    CustomerLookupService.ENTITY_CUSTOMER, limited.customerRef, details);
            throw limited.problem;
        }
    }

    /** The 429 to throw inside the transaction (reason {@code rate_limited}); audited after the rollback. */
    private RateLimited rateLimited(Actor agent, CustomerFacts facts, Method method, Window window,
                                    Instant now, String message) {
        Instant freeAt = window.oldest().orElse(now).plus(RATE_WINDOW);
        return new RateLimited(agent, facts.customerRef(), method, new TooManyRequestsException(
                message + " (customer " + facts.customerId() + ", agent " + agent.id() + ")",
                (Duration.between(now, freeAt).toMillis() + 999) / 1000));
    }

    /** Audits the refusal in its own transaction (§7) and returns the 422 to throw. */
    private IdentityCheckFailedException refused(Actor agent, String customerRef, Method method, long checkId,
                                                 FailureReason reason, Integer remaining) {
        Map<String, Object> details = new HashMap<>();
        details.put("method", method.name());
        details.put("checkId", checkId);
        details.put("reason", reason.value());
        details.put("attemptsRemaining", remaining);
        audit.recordIndependently(FAILED, Outcome.FAILURE, agent.id(), agent.username(),
                CustomerLookupService.ENTITY_CUSTOMER, customerRef, details);
        return new IdentityCheckFailedException("Identity check " + checkId + " refused", reason, remaining);
    }
}

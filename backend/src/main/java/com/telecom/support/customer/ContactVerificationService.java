package com.telecom.support.customer;

import com.telecom.support.audit.AuditEvent.Outcome;
import com.telecom.support.audit.AuditService;
import com.telecom.support.common.error.ApiExceptions.BusinessRuleException;
import com.telecom.support.common.error.ApiExceptions.DependencyUnavailableException;
import com.telecom.support.common.error.ApiExceptions.NotFoundException;
import com.telecom.support.common.error.ApiExceptions.TooManyRequestsException;
import com.telecom.support.common.security.ActorProvider;
import com.telecom.support.common.security.ActorProvider.Actor;
import com.telecom.support.common.security.CustomerScope;
import com.telecom.support.customer.ContactVerificationRepository.Starts;
import com.telecom.support.customer.ContactVerificationRepository.VerificationRow;
import com.telecom.support.customer.CustomerRepository.ProfileRow;
import com.telecom.support.customer.CustomerService.Profile;
import com.telecom.support.customer.VerificationCodeSender.Channel;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * OTP-verified contact change, E3 and E4 (design 02-customer backend R3, §3.2, §7, §8, R16; CUS-02.2, NFR-SEC).
 *
 * <p>Start: in one transaction that locks the customer row (so parallel starts cannot pass the hourly limit together),
 * R16 → 422, more than {@code starts-per-hour} starts in the last hour → 429, the channel's PENDING verification is
 * SUPERSEDED, a new PENDING row stores only the HMAC-SHA256 of the code, {@code CONTACT_VERIFICATION_STARTED} is
 * audited. The code is sent after the commit (no transaction open during the port call); a failed send marks the row
 * EXPIRED → 503.
 *
 * <p>Confirm (§3.2): (1) the attempt is counted by a guarded update committed in its own {@code REQUIRES_NEW}
 * transaction before anything is compared, so a 422 can never roll it back; (2) constant-time comparison of the HMACs;
 * (3) wrong → LOCKED by a guarded update once the maximum is reached, {@code CONTACT_VERIFICATION_FAILED} audited
 * independently, 422 with {@code attemptsRemaining}; (4) right → guarded PENDING → VERIFIED and, only for the single
 * winner, the contact is written and {@code PROFILE_CONTACT_CHANGED} audited in the same transaction.
 *
 * <p>The code is never logged, audited, put in an exception message or returned.
 */
@Service
@EnableConfigurationProperties(OtpProperties.class)
public class ContactVerificationService {

    static final String STARTED = "CONTACT_VERIFICATION_STARTED";
    static final String FAILED = "CONTACT_VERIFICATION_FAILED";
    static final String CONTACT_CHANGED = "PROFILE_CONTACT_CHANGED";
    static final Duration RATE_WINDOW = Duration.ofHours(1);

    private static final Logger log = LoggerFactory.getLogger(ContactVerificationService.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** The {@code ContactVerification} schema of openapi.yaml. */
    public record ContactVerification(long id, Channel channel, String maskedDestination, Instant expiresAt) {}

    /** Why a confirm was refused (422); also the audit {@code reason}. */
    public enum FailureReason {
        WRONG_CODE("wrong_code", "The code is incorrect."),
        EXPIRED("expired", "This code has expired or is no longer valid. Request a new code."),
        LOCKED("locked", "Too many wrong attempts. Request a new code.");

        private final String auditValue;
        private final String userDetail;

        FailureReason(String auditValue, String userDetail) {
            this.auditValue = auditValue;
            this.userDetail = userDetail;
        }

        public String auditValue() {
            return auditValue;
        }
    }

    /** 422 {@code business-rule} for a refused confirm; {@code attemptsRemaining} goes into the problem body. */
    public static class VerificationFailedException extends BusinessRuleException {
        private final transient FailureReason reason;
        private final int attemptsRemaining;

        VerificationFailedException(long verificationId, FailureReason reason, int attemptsRemaining) {
            super("Contact verification " + verificationId + " refused: " + reason.auditValue(), reason.userDetail);
            this.reason = reason;
            this.attemptsRemaining = attemptsRemaining;
        }

        public FailureReason reason() {
            return reason;
        }

        public int attemptsRemaining() {
            return attemptsRemaining;
        }
    }

    private final ContactVerificationRepository verifications;
    private final CustomerRepository customers;
    private final CustomerService profiles;
    private final AccountLookup accounts;
    private final CustomerScope scope;
    private final VerificationCodeSender sender;
    private final AuditService audit;
    private final ActorProvider actors;
    private final OtpProperties otp;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate newTx;
    private final SecretKeySpec hmacKey;
    private final SecureRandom random = new SecureRandom();

    ContactVerificationService(ContactVerificationRepository verifications, CustomerRepository customers,
                               CustomerService profiles, AccountLookup accounts, CustomerScope scope,
                               VerificationCodeSender sender, AuditService audit, ActorProvider actors,
                               OtpProperties otp, Clock clock, PlatformTransactionManager transactions) {
        this.verifications = verifications;
        this.customers = customers;
        this.profiles = profiles;
        this.accounts = accounts;
        this.scope = scope;
        this.sender = sender;
        this.audit = audit;
        this.actors = actors;
        this.otp = otp;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
        this.newTx = new TransactionTemplate(transactions);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        byte[] key = new byte[32];
        if (otp.hmacSecret() != null) {
            key = otp.hmacSecret().getBytes(StandardCharsets.UTF_8);
        } else {
            random.nextBytes(key);
            log.info("app.customer.otp.hmac-secret is not set: using a random per-process key");
        }
        this.hmacKey = new SecretKeySpec(key, HMAC_ALGORITHM);
    }

    /**
     * E3: starts a verification of {@code value} for {@code channel}. {@code value} is already format-checked
     * (controller); an email is lower-cased here. MSISDN equal to the primary → 422.
     */
    public ContactVerification start(Channel channel, String value) {
        long customerId = scope.requireCustomerId();
        Actor actor = actors.currentActor().orElseThrow();
        String destination = channel == Channel.EMAIL ? value.trim().toLowerCase(Locale.ROOT) : value.trim();
        String code = newCode();
        String codeHmac = hmac(code);

        ContactVerification started = tx.execute(status -> {
            if (!customers.lockForUpdate(customerId)) {
                throw new IllegalStateException("No linked customer " + customerId);
            }
            requireMutable(customerId);
            ProfileRow profile = customers.findProfile(customerId)
                    .orElseThrow(() -> new IllegalStateException("No profile for linked customer " + customerId));
            if (channel == Channel.SECONDARY_MSISDN && destination.equals(profile.primaryMsisdn())) {
                throw new BusinessRuleException("Secondary MSISDN equals the primary for customer " + customerId,
                        "The secondary number must differ from your primary mobile number.");
            }
            Instant now = clock.instant();
            Starts starts = verifications.startsSince(customerId, now.minus(RATE_WINDOW));
            if (starts.count() >= otp.startsPerHour()) {
                Instant freeAt = starts.oldest().orElse(now).plus(RATE_WINDOW);
                throw new TooManyRequestsException("Contact verification starts per hour exceeded for customer "
                        + customerId, (Duration.between(now, freeAt).toMillis() + 999) / 1000);
            }
            verifications.supersedePending(customerId, channel, now);
            Instant expiresAt = now.plus(otp.ttl());
            long id = verifications.insert(customerId, channel, destination, codeHmac, expiresAt, now);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("channel", channel.name());
            details.put("newValue", mask(channel, destination));
            audit.record(STARTED, actor.id(), actor.username(), CustomerService.ENTITY_CUSTOMER,
                    String.valueOf(customerId), details);
            return new ContactVerification(id, channel, mask(channel, destination), expiresAt);
        });

        try {
            sender.send(channel, destination, code);
        } catch (RuntimeException e) { // DeliveryException, or any other failure of the port
            newTx.executeWithoutResult(status -> verifications.markExpired(started.id(), clock.instant()));
            log.warn("Verification code for verification {} ({}) could not be delivered: {}", started.id(), channel,
                    e.getClass().getSimpleName());
            throw new DependencyUnavailableException("Verification code sender failed for verification "
                    + started.id());
        }
        return started;
    }

    /** E4: confirms {@code code} for the caller's verification {@code verificationId} (§3.2). */
    public Profile confirm(long verificationId, String code) {
        long customerId = scope.requireCustomerId();
        Actor actor = actors.currentActor().orElseThrow();
        requireMutable(customerId);
        int max = otp.maxAttempts();

        // §3.2 step 1: committed before any comparison; the row read under our own row lock gives our attempt number
        Optional<VerificationRow> counted = newTx.execute(status ->
                verifications.countAttempt(verificationId, customerId, max, clock.instant()) == 1
                        ? verifications.find(verificationId)
                        : Optional.<VerificationRow>empty());
        if (counted.isEmpty()) {
            VerificationRow row = verifications.find(verificationId)
                    .filter(r -> r.customerId() == customerId)
                    .orElseThrow(() -> new NotFoundException("Contact verification " + verificationId
                            + " not found for customer " + customerId));
            boolean locked = ContactVerificationRepository.LOCKED.equals(row.status())
                    || (ContactVerificationRepository.PENDING.equals(row.status()) && row.attempts() >= max);
            throw refused(actor, row, locked ? FailureReason.LOCKED : FailureReason.EXPIRED,
                    Math.max(0, max - row.attempts()));
        }
        VerificationRow row = counted.get();

        // step 2: constant time, over the stored HMACs only
        boolean match = MessageDigest.isEqual(hmac(code).getBytes(StandardCharsets.US_ASCII),
                row.codeHmac().getBytes(StandardCharsets.US_ASCII));
        if (!match) {
            // step 3
            if (row.attempts() >= max) {
                newTx.executeWithoutResult(status -> verifications.lock(verificationId, clock.instant()));
            }
            throw refused(actor, row, FailureReason.WRONG_CODE, Math.max(0, max - row.attempts()));
        }

        // step 4: only the single PENDING → VERIFIED winner writes the contact and audits
        boolean won = Boolean.TRUE.equals(tx.execute(status -> {
            Instant now = clock.instant();
            if (verifications.markVerified(verificationId, now) != 1) {
                return false;
            }
            ProfileRow before = customers.findProfile(customerId)
                    .orElseThrow(() -> new IllegalStateException("No profile for linked customer " + customerId));
            boolean email = row.channel() == Channel.EMAIL;
            customers.updateVerifiedContact(customerId, email, row.newValue(), now);
            String old = email ? before.email() : before.secondaryMsisdn();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("channel", row.channel().name());
            details.put("old", old == null ? null : mask(row.channel(), old));
            details.put("new", mask(row.channel(), row.newValue()));
            audit.record(CONTACT_CHANGED, actor.id(), actor.username(), CustomerService.ENTITY_CUSTOMER,
                    String.valueOf(customerId), details);
            return true;
        }));
        if (!won) {
            throw refused(actor, row, FailureReason.EXPIRED, Math.max(0, max - row.attempts()));
        }
        return profiles.myProfile();
    }

    private void requireMutable(long customerId) {
        accounts.accountFor(customerId)
                .orElseThrow(() -> new IllegalStateException("Customer " + customerId + " has no account"))
                .requireMutable();
    }

    /** Audits the refusal in its own transaction (§7) and returns the 422 to throw. */
    private VerificationFailedException refused(Actor actor, VerificationRow row, FailureReason reason, int remaining) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("channel", row.channel().name());
        details.put("reason", reason.auditValue());
        details.put("attemptsRemaining", remaining);
        audit.recordIndependently(FAILED, Outcome.FAILURE, actor.id(), actor.username(),
                CustomerService.ENTITY_CUSTOMER, String.valueOf(row.customerId()), details);
        return new VerificationFailedException(row.id(), reason, remaining);
    }

    private String newCode() {
        return otp.devFixedCode() != null ? otp.devFixedCode() : String.format("%06d", random.nextInt(1_000_000));
    }

    /** Hex HMAC-SHA256 of the code (64 characters, the {@code code_hmac} column). */
    String hmac(String code) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(hmacKey);
            return HexFormat.of().formatHex(mac.doFinal(code.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** §7 masking: email {@code ra***@ex***.com}; MSISDN only its last 4 digits. */
    static String mask(Channel channel, String value) {
        if (channel == Channel.SECONDARY_MSISDN) {
            return value.length() <= 4 ? "****" : "*".repeat(value.length() - 4) + value.substring(value.length() - 4);
        }
        int at = value.lastIndexOf('@');
        if (at < 0) {
            return "***";
        }
        String local = value.substring(0, at);
        String domain = value.substring(at + 1);
        int dot = domain.lastIndexOf('.');
        String host = dot < 0 ? domain : domain.substring(0, dot);
        String tld = dot < 0 ? "" : domain.substring(dot);
        return prefix(local) + "***@" + prefix(host) + "***" + tld;
    }

    private static String prefix(String s) {
        return s.substring(0, Math.min(2, s.length()));
    }
}

package com.telecom.support.customer;

import com.telecom.support.audit.AuditService;
import com.telecom.support.common.error.ApiExceptions.TooManyRequestsException;
import com.telecom.support.common.security.ActorProvider;
import com.telecom.support.common.security.ActorProvider.Actor;
import com.telecom.support.customer.CustomerLookupRepository.MatchRow;
import com.telecom.support.customer.VerificationCodeSender.Channel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Collectors;
import java.util.Arrays;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * I1 customer search (design 03-inbound backend R1, R2, §7; INB-01.1, INB-01.4, NFR-PRIV). Exact E.164 match on a live
 * subscription; the result is masked and, on a hit, carries an opaque 128-bit lookup token (valid {@code lookup-token-ttl}
 * for this agent and this customer only; only its SHA-256 is stored) that starting an identity check requires. Every
 * search that passes the per-agent rate limit is audited ({@code CUSTOMER_LOOKUP}, hit or miss) without the full MSISDN.
 * The MSISDN is never logged.
 */
@Service
@EnableConfigurationProperties(InboundProperties.class)
public class CustomerLookupService {

    static final String LOOKUP = "CUSTOMER_LOOKUP";
    static final String ENTITY_CUSTOMER = "CUSTOMER";
    private static final int TOKEN_BYTES = 16;

    /** The {@code CustomerMatch} schema of openapi.yaml. */
    public record CustomerMatch(String lookupToken, long customerId, String customerRef, String maskedName,
                                String maskedMsisdn, String subscriptionStatus, String customerStatus,
                                String accountStatus, Instant verifiedUntil) {

        /** Never print the token (logs, debuggers). */
        @Override
        public String toString() {
            return "CustomerMatch[customerRef=" + customerRef + ", lookupToken=***]";
        }
    }

    private final CustomerLookupRepository lookups;
    private final LookupRateLimiter limiter;
    private final AuditService audit;
    private final ActorProvider actors;
    private final InboundProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final SecureRandom random = new SecureRandom();

    CustomerLookupService(CustomerLookupRepository lookups, LookupRateLimiter limiter, AuditService audit,
                          ActorProvider actors, InboundProperties properties, Clock clock,
                          PlatformTransactionManager transactions) {
        this.lookups = lookups;
        this.limiter = limiter;
        this.audit = audit;
        this.actors = actors;
        this.properties = properties;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    /** I1: searches {@code msisdn} (already E.164-validated by the controller). 0 or 1 match. */
    public List<CustomerMatch> search(String msisdn) {
        Actor agent = actors.currentActor().orElseThrow();
        OptionalLong throttled = limiter.acquire(agent.id());
        if (throttled.isPresent()) {
            throw new TooManyRequestsException("Customer searches per minute exceeded for agent " + agent.id(),
                    throttled.getAsLong());
        }
        return tx.execute(status -> {
            Instant now = clock.instant();
            Optional<MatchRow> hit = lookups.findByLiveMsisdn(msisdn);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("maskedMsisdn", ContactVerificationService.mask(Channel.SECONDARY_MSISDN, msisdn));
            details.put("matched", hit.isPresent());
            audit.record(LOOKUP, agent.id(), agent.username(), hit.isPresent() ? ENTITY_CUSTOMER : null,
                    hit.map(MatchRow::customerRef).orElse(null), details);
            if (hit.isEmpty()) {
                return List.<CustomerMatch>of();
            }
            MatchRow row = hit.get();
            String token = newToken();
            lookups.insert(agent.id(), row.customerId(), tokenHash(token), now.plus(properties.lookupTokenTtl()), now);
            return List.of(new CustomerMatch(token, row.customerId(), row.customerRef(), maskName(row.fullName()),
                    maskMsisdn(row.msisdn()), row.subscriptionStatus(), row.customerStatus(), row.accountStatus(),
                    lookups.verifiedUntil(agent.id(), row.customerId(), now).orElse(null)));
        });
    }

    /** R2: whether {@code token} is this agent's unexpired lookup token for {@code customerId}. */
    boolean isValidToken(String token, long agentUserId, long customerId) {
        return lookups.isValid(tokenHash(token), agentUserId, customerId, clock.instant());
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Hex SHA-256 of the token ({@code customer_lookups.token_hash}). */
    static String tokenHash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Name initials, e.g. {@code R*** K***}. */
    static String maskName(String fullName) {
        return Arrays.stream(fullName.trim().split("\\s+"))
                .filter(w -> !w.isEmpty())
                .map(w -> w.substring(0, w.offsetByCodePoints(0, 1)) + "***")
                .collect(Collectors.joining(" "));
    }

    /** E.164 shown as its first 3 characters and last 4 digits, e.g. {@code +91******0001}. */
    static String maskMsisdn(String msisdn) {
        if (msisdn.length() <= 7) {
            return "*".repeat(Math.max(0, msisdn.length() - 4)) + msisdn.substring(Math.max(0, msisdn.length() - 4));
        }
        return msisdn.substring(0, 3) + "*".repeat(msisdn.length() - 7) + msisdn.substring(msisdn.length() - 4);
    }
}

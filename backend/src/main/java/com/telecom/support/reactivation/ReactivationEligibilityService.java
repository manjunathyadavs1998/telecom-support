package com.telecom.support.reactivation;

import com.telecom.support.customer.AccountLookup;
import com.telecom.support.customer.AccountLookup.AccountStatus;
import com.telecom.support.customer.AccountLookup.CustomerAccount;
import com.telecom.support.customer.AccountLookup.SuspensionReason;
import com.telecom.support.customer.VerifiedCustomer;
import com.telecom.support.reactivation.ReactivationPolicyRepository.Policy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reactivation eligibility I13 (design 03-inbound backend R13; INB-04.1/04.2/04.6, §14 #5). Every rule is checked and
 * each one that fails adds its reason code: customer ACTIVE ({@code CUSTOMER_INACTIVE}); account SUSPENDED
 * ({@code NOT_SUSPENDED}); reason not FRAUD/LEGAL/REGULATORY, hard-coded whatever the policy says
 * ({@code PROHIBITED_REASON}); the reason's policy eligible ({@code REASON_NOT_ELIGIBLE}); at least one SUSPENDED
 * subscription ({@code NO_SUSPENDED_SERVICE}); no open request ({@code OPEN_REQUEST}); fewer than
 * {@code app.reactivation.account-limit} non-FAILED requests of the account within {@code account-window}
 * ({@code ACCOUNT_LIMIT_REACHED}); fewer than {@code agent-daily-limit} non-FAILED requests by the agent since the
 * start of today in {@code app.display-time-zone} ({@code AGENT_LIMIT_REACHED}). {@code maxHours} is the policy's
 * (at most 24), null when the account is not suspended. Create (R14) re-runs {@link #evaluate} under its locks.
 */
@Service
@EnableConfigurationProperties(ReactivationProperties.class)
public class ReactivationEligibilityService {

    /** The {@code EligibilityReason} schema ({@link #HOURS_EXCEED_POLICY} only in a create 422). */
    public enum EligibilityReason {
        CUSTOMER_INACTIVE, NOT_SUSPENDED, PROHIBITED_REASON, REASON_NOT_ELIGIBLE, NO_SUSPENDED_SERVICE, OPEN_REQUEST,
        ACCOUNT_LIMIT_REACHED, AGENT_LIMIT_REACHED, HOURS_EXCEED_POLICY
    }

    /** The {@code ReactivationEligibility} schema; {@code reasons} empty exactly when {@code eligible}. */
    public record ReactivationEligibility(boolean eligible, List<EligibilityReason> reasons,
                                          SuspensionReason suspensionReason, Integer maxHours,
                                          List<String> suspendedMsisdns, String openRequestReference) {}

    /** R13: never eligible, whatever {@code reactivation_policies} says. */
    static final Set<SuspensionReason> PROHIBITED =
            EnumSet.of(SuspensionReason.FRAUD, SuspensionReason.LEGAL, SuspensionReason.REGULATORY);
    static final int MAX_HOURS = 24;

    private final AccountLookup accounts;
    private final ReactivationPolicyRepository policies;
    private final ReactivationRepository requests;
    private final ReactivationProperties properties;
    private final Clock clock;
    private final ZoneId zone;

    ReactivationEligibilityService(AccountLookup accounts, ReactivationPolicyRepository policies,
                                   ReactivationRepository requests, ReactivationProperties properties, Clock clock,
                                   @Value("${app.display-time-zone:Asia/Kolkata}") ZoneId zone) {
        this.accounts = accounts;
        this.policies = policies;
        this.requests = requests;
        this.properties = properties;
        this.clock = clock;
        this.zone = zone;
    }

    /** I13 for the verified customer's account and the calling agent. */
    @Transactional(readOnly = true)
    public ReactivationEligibility eligibilityOf(VerifiedCustomer verified) {
        CustomerAccount account = accounts.accountFor(verified.customerId())
                .orElseThrow(() -> new IllegalStateException("Verified customer " + verified.customerId()
                        + " has no account"));
        return evaluate(account, verified.agentUserId());
    }

    /** R13 for {@code account} (as read, or as locked by R14) and {@code agentUserId}. */
    ReactivationEligibility evaluate(CustomerAccount account, long agentUserId) {
        List<EligibilityReason> reasons = new ArrayList<>();
        if (!account.customerActive()) {
            reasons.add(EligibilityReason.CUSTOMER_INACTIVE);
        }
        SuspensionReason suspensionReason = null;
        Integer maxHours = null;
        if (account.status() != AccountStatus.SUSPENDED) {
            reasons.add(EligibilityReason.NOT_SUSPENDED);
        } else {
            suspensionReason = account.suspensionReason();
            if (PROHIBITED.contains(suspensionReason)) {
                reasons.add(EligibilityReason.PROHIBITED_REASON);
            }
            Optional<Policy> policy = policies.find(suspensionReason.name());
            if (policy.isEmpty() || !policy.get().eligible()) {
                reasons.add(EligibilityReason.REASON_NOT_ELIGIBLE);
            }
            maxHours = policy.map(p -> Math.min(MAX_HOURS, p.maxHours())).orElse(null);
        }
        List<String> msisdns = accounts.suspendedMsisdns(account.accountId());
        if (msisdns.isEmpty()) {
            reasons.add(EligibilityReason.NO_SUSPENDED_SERVICE);
        }
        String openReference = requests.findOpenReference(account.accountId()).orElse(null);
        if (openReference != null) {
            reasons.add(EligibilityReason.OPEN_REQUEST);
        }
        Instant now = clock.instant();
        if (requests.countNonFailedForAccountSince(account.accountId(), now.minus(properties.accountWindow()))
                >= properties.accountLimit()) {
            reasons.add(EligibilityReason.ACCOUNT_LIMIT_REACHED);
        }
        Instant startOfToday = LocalDate.ofInstant(now, zone).atStartOfDay(zone).toInstant();
        if (requests.countNonFailedByAgentSince(agentUserId, startOfToday) >= properties.agentDailyLimit()) {
            reasons.add(EligibilityReason.AGENT_LIMIT_REACHED);
        }
        return new ReactivationEligibility(reasons.isEmpty(), List.copyOf(reasons), suspensionReason, maxHours,
                List.copyOf(msisdns), openReference);
    }
}

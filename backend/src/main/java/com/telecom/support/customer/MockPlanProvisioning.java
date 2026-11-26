package com.telecom.support.customer;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * In-memory {@link PlanProvisioning} (design 02-customer backend §8): {@link Mode#NORMAL} succeeds, {@link Mode#FAIL}
 * throws {@link ProvisioningException}. The last {@link #MAX_RECORDED} successful calls are kept for tests.
 */
@Component
public class MockPlanProvisioning implements PlanProvisioning {

    public enum Mode { NORMAL, FAIL }

    /** One successful {@link #apply} call. */
    public record Applied(String msisdn, String toPlanCode, LocalDate effectiveDate) {}

    static final int MAX_RECORDED = 100;

    private final Deque<Applied> applied = new ArrayDeque<>();
    private volatile Mode mode = Mode.NORMAL;

    @Override
    public void apply(String msisdn, String toPlanCode, LocalDate effectiveDate) {
        if (mode == Mode.FAIL) {
            throw new ProvisioningException("Mock provisioning is set to fail (plan " + toPlanCode + ")");
        }
        synchronized (applied) {
            if (applied.size() == MAX_RECORDED) {
                applied.removeFirst();
            }
            applied.addLast(new Applied(msisdn, toPlanCode, effectiveDate));
        }
    }

    public Mode mode() {
        return mode;
    }

    /** Switches the behaviour of following calls (tests). */
    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.NORMAL : mode;
    }

    /** Successful calls, oldest first (tests). */
    public List<Applied> applied() {
        synchronized (applied) {
            return List.copyOf(applied);
        }
    }

    /** Forgets recorded calls and returns to {@link Mode#NORMAL} (tests). */
    public void reset() {
        mode = Mode.NORMAL;
        synchronized (applied) {
            applied.clear();
        }
    }
}

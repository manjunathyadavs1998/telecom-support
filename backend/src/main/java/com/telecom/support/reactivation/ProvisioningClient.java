package com.telecom.support.reactivation;

import java.time.Instant;
import java.util.List;

/**
 * Port to the network provisioning system that temporarily restores and re-suspends a suspended account's services
 * (design 03-inbound backend §8, §3.1; INB-04.4/04.5, NFR-AVAIL). Every call is idempotent by {@code requestRef} (the
 * {@code REA} reference), runs outside any transaction and is bounded by {@code app.reactivation.provisioning.timeout}
 * (default 5 s). Slice 03 ships only {@link MockProvisioningClient}.
 */
public interface ProvisioningClient {

    /**
     * Restores {@code msisdns} for {@code requestRef}. {@code until} is advisory (provider record only): expiry is
     * enforced by this backend (R15).
     *
     * @throws ProvisioningRejectedException    the provider refused the request (business rule) → FAILED
     * @throws ProvisioningUnavailableException the provider could not be reached (connection refused): nothing happened
     * @throws ProvisioningTimeoutException     no answer in time, or a 5xx: the outcome is unknown, reconciled later
     */
    RestoreResult restore(String requestRef, List<String> msisdns, Instant until);

    /**
     * Suspends {@code msisdns} again for {@code requestRef}.
     *
     * @throws ProvisioningException the suspension was not confirmed (unavailable, timeout); retried with backoff
     */
    void suspend(String requestRef, List<String> msisdns);

    /**
     * What the provider knows about {@code requestRef}.
     *
     * @throws ProvisioningException the provider could not be reached or did not answer; retried with backoff
     */
    ProviderStatus getStatus(String requestRef);

    /** A confirmed restoration. */
    record RestoreResult(String providerRef) {}

    /** {@link ProviderStatus#state()} values. */
    enum State { RESTORED, SUSPENDED, NOT_FOUND }

    /** {@code restoredAt} and {@code providerRef} are set only for {@link State#RESTORED}. */
    record ProviderStatus(State state, Instant restoredAt, String providerRef) {

        public static ProviderStatus restored(Instant restoredAt, String providerRef) {
            return new ProviderStatus(State.RESTORED, restoredAt, providerRef);
        }

        public static ProviderStatus suspended() {
            return new ProviderStatus(State.SUSPENDED, null, null);
        }

        public static ProviderStatus notFound() {
            return new ProviderStatus(State.NOT_FOUND, null, null);
        }
    }

    /** A provisioning call did not succeed. Messages never contain an MSISDN. */
    abstract class ProvisioningException extends RuntimeException {
        protected ProvisioningException(String message) {
            super(message);
        }
    }

    /** The provider refused the request for a business reason (restore only). */
    class ProvisioningRejectedException extends ProvisioningException {
        public ProvisioningRejectedException(String message) {
            super(message);
        }
    }

    /** The provider could not be reached (connection refused): the call had no effect. */
    class ProvisioningUnavailableException extends ProvisioningException {
        public ProvisioningUnavailableException(String message) {
            super(message);
        }
    }

    /** No answer within the client timeout, or a 5xx: the outcome is unknown. */
    class ProvisioningTimeoutException extends ProvisioningException {
        public ProvisioningTimeoutException(String message) {
            super(message);
        }
    }
}

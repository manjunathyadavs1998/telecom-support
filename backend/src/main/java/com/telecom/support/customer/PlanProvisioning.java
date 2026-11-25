package com.telecom.support.customer;

import java.time.LocalDate;

/**
 * Port to the provisioning system that switches a subscription's plan (design 02-customer backend §8, R6; CUS-03.5,
 * BR-08). Slice 02 ships only {@link MockPlanProvisioning}; the real adapter comes in slice 06.
 */
public interface PlanProvisioning {

    /**
     * Applies {@code toPlanCode} to {@code msisdn} from {@code effectiveDate}.
     *
     * @throws ProvisioningException when the provider refuses or fails (R6: the change stays SCHEDULED and is retried)
     */
    void apply(String msisdn, String toPlanCode, LocalDate effectiveDate);

    /** The provider could not apply the change. */
    class ProvisioningException extends RuntimeException {
        public ProvisioningException(String message) {
            super(message);
        }
    }
}

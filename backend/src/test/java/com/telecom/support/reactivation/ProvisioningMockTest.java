package com.telecom.support.reactivation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.telecom.support.common.IdempotencyServiceIT.MutableClock;
import com.telecom.support.reactivation.MockProvisioningClient.Mode;
import com.telecom.support.reactivation.MockProvisioningClient.Operation;
import com.telecom.support.reactivation.ProvisioningClient.ProviderStatus;
import com.telecom.support.reactivation.ProvisioningClient.ProvisioningRejectedException;
import com.telecom.support.reactivation.ProvisioningClient.ProvisioningTimeoutException;
import com.telecom.support.reactivation.ProvisioningClient.ProvisioningUnavailableException;
import com.telecom.support.reactivation.ProvisioningClient.State;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * B15: {@link MockProvisioningClient} modes and {@link ReactivationProperties} defaults (design 03-inbound backend §8,
 * §3.1, §6 config; INB-04.4/04.5, NFR-AVAIL).
 */
@ExtendWith(OutputCaptureExtension.class)
class ProvisioningMockTest {

    private static final Instant T0 = Instant.parse("2030-01-15T06:00:00Z");
    private static final List<String> MSISDNS = List.of("+919900000006", "+919900000016");
    private static final Instant UNTIL = T0.plus(Duration.ofHours(24));

    private final MutableClock clock = new MutableClock(T0);

    @Test
    void propertiesDefaultAndValidate() {
        ReactivationProperties defaults = new ReactivationProperties(null, null, null, null, null, null, null, null);

        assertThat(defaults.accountLimit()).isEqualTo(1);
        assertThat(defaults.accountWindow()).isEqualTo(Duration.ofDays(30));
        assertThat(defaults.agentDailyLimit()).isEqualTo(10);
        assertThat(defaults.attentionAfter()).isEqualTo(Duration.ofMinutes(30));
        assertThat(defaults.attentionFailures()).isEqualTo(5);
        assertThat(defaults.provisioning().timeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(defaults.provisioning().mock().enabled()).isFalse();
        assertThat(defaults.provisioning().mock().mode()).isEqualTo(Mode.NORMAL);
        assertThatThrownBy(() -> new ReactivationProperties(0, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("account-limit");
        assertThatThrownBy(() -> new ReactivationProperties.Provisioning(Duration.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("provisioning.timeout");
    }

    @Test
    void normalRestoreIsIdempotentByReferenceAndStatusFollowsSuspend() {
        MockProvisioningClient client = client(Mode.NORMAL);
        assertThat(client.getStatus("REA-1")).isEqualTo(ProviderStatus.notFound());

        String providerRef = client.restore("REA-1", MSISDNS, UNTIL).providerRef();
        clock.set(T0.plusSeconds(90));
        assertThat(client.restore("REA-1", MSISDNS, UNTIL).providerRef()).as("idempotent").isEqualTo(providerRef);
        assertThat(client.getStatus("REA-1")).isEqualTo(ProviderStatus.restored(T0, providerRef));

        client.suspend("REA-1", MSISDNS);
        client.suspend("REA-1", MSISDNS);
        assertThat(client.getStatus("REA-1")).isEqualTo(ProviderStatus.suspended());
        assertThat(client.callsFor("REA-1")).extracting(MockProvisioningClient.Call::operation).containsExactly(
                Operation.GET_STATUS, Operation.RESTORE, Operation.RESTORE, Operation.GET_STATUS, Operation.SUSPEND,
                Operation.SUSPEND, Operation.GET_STATUS);
        assertThat(client.callsFor("REA-1").get(1).msisdns()).isEqualTo(MSISDNS);
        assertThat(client.callsFor("REA-1").get(1).until()).isEqualTo(UNTIL);
    }

    @Test
    void rejectRefusesRestoreOnly() {
        MockProvisioningClient client = client(Mode.REJECT);

        assertThatThrownBy(() -> client.restore("REA-2", MSISDNS, UNTIL))
                .isInstanceOf(ProvisioningRejectedException.class).message().doesNotContain("+9199");
        assertThat(client.getStatus("REA-2").state()).isEqualTo(State.NOT_FOUND);
    }

    @Test
    void unavailableRefusesEveryCallAndChangesNothing() {
        MockProvisioningClient client = client(Mode.NORMAL);
        client.restore("REA-3", MSISDNS, UNTIL);
        client.setMode(Mode.UNAVAILABLE);

        assertThatThrownBy(() -> client.restore("REA-4", MSISDNS, UNTIL))
                .isInstanceOf(ProvisioningUnavailableException.class);
        assertThatThrownBy(() -> client.suspend("REA-3", MSISDNS)).isInstanceOf(ProvisioningUnavailableException.class);
        assertThatThrownBy(() -> client.getStatus("REA-3")).isInstanceOf(ProvisioningUnavailableException.class);

        client.setMode(Mode.NORMAL);
        assertThat(client.getStatus("REA-3").state()).isEqualTo(State.RESTORED);
        assertThat(client.getStatus("REA-4").state()).isEqualTo(State.NOT_FOUND);
    }

    @Test
    void timeoutLeavesTheOutcomeUnknownButTheProviderActed() {
        MockProvisioningClient client = client(Mode.TIMEOUT);
        client.setTimeoutDelay(Duration.ofMillis(10));

        assertThatThrownBy(() -> client.restore("REA-5", MSISDNS, UNTIL))
                .isInstanceOf(ProvisioningTimeoutException.class).hasMessageContaining("timed out after PT5S");
        assertThatThrownBy(() -> client.getStatus("REA-5")).isInstanceOf(ProvisioningTimeoutException.class);

        client.setMode(Mode.NORMAL);
        assertThat(client.getStatus("REA-5").state()).as("restore reached the provider").isEqualTo(State.RESTORED);

        client.setMode(Mode.TIMEOUT);
        assertThatThrownBy(() -> client.suspend("REA-5", MSISDNS)).isInstanceOf(ProvisioningTimeoutException.class);
        client.setMode(Mode.NORMAL);
        assertThat(client.getStatus("REA-5").state()).as("suspend reached the provider").isEqualTo(State.SUSPENDED);
    }

    @Test
    void timeoutWaitsForTheConfiguredTimeoutByDefault() {
        MockProvisioningClient client = new MockProvisioningClient(properties(Mode.TIMEOUT, Duration.ofMillis(200)),
                clock);
        long start = System.nanoTime();

        assertThatThrownBy(() -> client.getStatus("REA-6")).isInstanceOf(ProvisioningTimeoutException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(190));
    }

    @Test
    void suspendFailRefusesSuspendOnly() {
        MockProvisioningClient client = client(Mode.SUSPEND_FAIL);
        client.restore("REA-7", MSISDNS, UNTIL);

        assertThatThrownBy(() -> client.suspend("REA-7", MSISDNS)).isInstanceOf(ProvisioningUnavailableException.class);
        assertThat(client.getStatus("REA-7").state()).isEqualTo(State.RESTORED);
    }

    @Test
    void testSeamsAndResetReturnToTheConfiguredMode(CapturedOutput output) {
        MockProvisioningClient client = client(Mode.REJECT);
        client.putStatus("REA-8", State.RESTORED, T0.minus(Duration.ofHours(30)));
        assertThat(client.getStatus("REA-8")).isEqualTo(ProviderStatus.restored(T0.minus(Duration.ofHours(30)),
                "PRV-REA-8"));
        client.putStatus("REA-8", State.NOT_FOUND, null);
        assertThat(client.statusOf("REA-8")).isEmpty();

        client.setMode(Mode.NORMAL);
        client.restore("REA-9", MSISDNS, UNTIL);
        client.reset();

        assertThat(client.mode()).isEqualTo(Mode.REJECT);
        assertThat(client.calls()).isEmpty();
        assertThat(client.statusOf("REA-9")).isEmpty();
        assertThat(output.getAll()).doesNotContain("+919900000006");
    }

    private MockProvisioningClient client(Mode mode) {
        return new MockProvisioningClient(properties(mode, null), clock);
    }

    private static ReactivationProperties properties(Mode mode, Duration timeout) {
        return new ReactivationProperties(null, null, null, null, null, null, null,
                new ReactivationProperties.Provisioning(timeout, new ReactivationProperties.Mock(true, mode)));
    }
}

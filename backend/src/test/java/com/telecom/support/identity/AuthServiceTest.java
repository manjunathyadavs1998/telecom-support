package com.telecom.support.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.telecom.support.audit.AuditService;
import com.telecom.support.identity.AuthService.BadCredentialsException;
import com.telecom.support.identity.UserRepository.UserRow;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * C15: the credential check reads only the {@code users} row before BCrypt; roles and permissions are loaded only after
 * a successful match, so known and unknown usernames run the same queries before the hash check (01 backend §4.1).
 */
class AuthServiceTest {

    private static final String PASSWORD = "Password@123";
    private static final String HASH = new BCryptPasswordEncoder().encode(PASSWORD);

    private UserRepository users;
    private AuthService auth;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        Clock clock = Clock.systemUTC();
        auth = new AuthService(users, new LoginRateLimiter(clock), mock(AuditService.class), clock,
                mock(PlatformTransactionManager.class));
    }

    @Test
    void unknownUsernameRunsOnlyTheRowLookup() {
        when(users.findRow("nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> auth.authenticate("198.51.100.1", "nobody", PASSWORD))
                .isInstanceOf(BadCredentialsException.class);
        verify(users).findRow("nobody");
        verifyNoMoreInteractions(users);
    }

    @Test
    void knownUsernameWithBadPasswordRunsOnlyTheRowLookup() {
        when(users.findRow("agent01")).thenReturn(Optional.of(row("ACTIVE")));

        assertThatThrownBy(() -> auth.authenticate("198.51.100.2", "agent01", "Wrong-password-1"))
                .isInstanceOf(BadCredentialsException.class);
        verify(users).findRow("agent01");
        verifyNoMoreInteractions(users);
    }

    @Test
    void disabledUserNeverLoadsAuthorities() {
        when(users.findRow("agent01")).thenReturn(Optional.of(row("DISABLED")));

        assertThatThrownBy(() -> auth.authenticate("198.51.100.3", "agent01", PASSWORD))
                .isInstanceOf(BadCredentialsException.class);
        verify(users, never()).withAuthorities(any());
    }

    @Test
    void authoritiesAreLoadedOnlyAfterTheHashMatched() {
        UserRow row = row("ACTIVE");
        when(users.findRow("agent01")).thenReturn(Optional.of(row));
        when(users.withAuthorities(row)).thenReturn(new CurrentUser(7L, "agent01", HASH, "Agent",
                CurrentUser.UserType.STAFF, null, 1L, true, Set.of("AGENT"), Set.of("CASE_READ")));

        CurrentUser user = auth.authenticate("198.51.100.4", "agent01", PASSWORD);

        assertThat(user.id()).isEqualTo(7L);
        assertThat(user.getPassword()).isNull();
        InOrder order = inOrder(users);
        order.verify(users).findRow("agent01");
        order.verify(users).withAuthorities(row);
    }

    private static UserRow row(String status) {
        return new UserRow(7L, "agent01", HASH, "Agent", "STAFF", null, 1L, status);
    }
}

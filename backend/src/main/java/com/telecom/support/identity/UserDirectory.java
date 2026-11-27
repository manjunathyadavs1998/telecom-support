package com.telecom.support.identity;

import com.telecom.support.common.error.ApiExceptions.NotFoundException;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Identity's public directory for feature modules (design 00-overview rev 4 §1; 03-inbound backend §6.1): user display
 * names and team names for case lists and details (R8, R9), and the user row lock that serialises one agent's
 * reactivation creates (R14). Identity imports no feature module; callers pass ids they already hold.
 */
@Service
public class UserDirectory {

    private final UserRepository users;

    UserDirectory(UserRepository users) {
        this.users = users;
    }

    /** User id → display name; null and unknown ids are absent from the result. One query, none for no ids. */
    public Map<Long, String> displayNames(Collection<Long> userIds) {
        Set<Long> ids = distinct(userIds);
        return ids.isEmpty() ? Map.of() : Map.copyOf(users.findDisplayNames(ids));
    }

    /** Team id → team name; null and unknown ids are absent from the result. One query, none for no ids. */
    public Map<Long, String> teamNames(Collection<Long> teamIds) {
        Set<Long> ids = distinct(teamIds);
        return ids.isEmpty() ? Map.of() : Map.copyOf(users.findTeamNames(ids));
    }

    /**
     * Locks the {@code users} row until the caller's transaction ends (R14: taken before the account lock, fixed
     * order). An unknown id is 404.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForUpdate(long userId) {
        if (!users.lockForUpdate(userId)) {
            throw new NotFoundException("User " + userId + " not found");
        }
    }

    private static Set<Long> distinct(Collection<Long> ids) {
        return ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
    }
}

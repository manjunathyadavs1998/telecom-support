package com.telecom.support.identity;

import com.telecom.support.identity.CurrentUser.UserType;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read side of {@code users}, {@code user_roles}, {@code role_permissions} (design 01-foundation backend §2, §3). */
@Repository
public class UserRepository {

    /** One {@code users} row without authorities: what the credential check needs before the hash matches (§4.1). */
    record UserRow(long id, String username, String passwordHash, String displayName, String userType,
                   Long customerId, Long teamId, String status) {

        boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    private static final String FIND_BY_USERNAME = """
            SELECT id, username, password_hash, display_name, user_type, customer_id, team_id, status
            FROM users
            WHERE username = :username
            """;

    private static final String FIND_ROLES = """
            SELECT r.code
            FROM user_roles ur
            JOIN roles r ON r.id = ur.role_id
            WHERE ur.user_id = :userId
            """;

    /** Union of the permissions of all the user's roles (roles add up, §3). */
    private static final String FIND_PERMISSIONS = """
            SELECT DISTINCT p.code
            FROM user_roles ur
            JOIN role_permissions rp ON rp.role_id = ur.role_id
            JOIN permissions p ON p.id = rp.permission_id
            WHERE ur.user_id = :userId
            """;

    /** Status only: the per-request DISABLED re-check of {@code SessionPolicyFilter} (§4.3). */
    private static final String FIND_STATUS = """
            SELECT status
            FROM users
            WHERE id = :id
            """;

    private static final String UPDATE_LAST_LOGIN = """
            UPDATE users
            SET last_login_at = :lastLoginAt
            WHERE id = :id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public UserRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The user with roles and permissions, or empty for an unknown username. */
    public Optional<CurrentUser> findByUsername(String username) {
        return findRow(username).map(this::withAuthorities);
    }

    /**
     * The {@code users} row only (one query, no roles or permissions), or empty for an unknown username. The login
     * credential check uses it so known and unknown usernames run the same queries before BCrypt (§4.1).
     */
    Optional<UserRow> findRow(String username) {
        return jdbc.query(FIND_BY_USERNAME, Map.of("username", username), (rs, i) -> new UserRow(
                        rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"),
                        rs.getString("display_name"), rs.getString("user_type"),
                        rs.getObject("customer_id", Long.class), rs.getObject("team_id", Long.class),
                        rs.getString("status")))
                .stream()
                .findFirst();
    }

    /** {@code users.status = ACTIVE}; false for a DISABLED or deleted user. */
    public boolean isActive(long id) {
        return jdbc.queryForList(FIND_STATUS, Map.of("id", id), String.class).stream()
                .findFirst()
                .map("ACTIVE"::equals)
                .orElse(false);
    }

    /** Sets {@code last_login_at} (UTC) after a successful login (§4). */
    public void updateLastLoginAt(long id, Instant at) {
        jdbc.update(UPDATE_LAST_LOGIN, Map.of("id", id, "lastLoginAt", LocalDateTime.ofInstant(at, ZoneOffset.UTC)));
    }

    /** Loads the row's roles and permissions (two queries) into the principal. */
    CurrentUser withAuthorities(UserRow row) {
        Map<String, Long> params = Map.of("userId", row.id());
        Set<String> roles = new TreeSet<>(codes(FIND_ROLES, params));
        Set<String> permissions = new TreeSet<>(codes(FIND_PERMISSIONS, params));
        return new CurrentUser(row.id(), row.username(), row.passwordHash(), row.displayName(),
                UserType.valueOf(row.userType()), row.customerId(), row.teamId(), row.active(),
                roles, permissions);
    }

    private List<String> codes(String sql, Map<String, Long> params) {
        return jdbc.queryForList(sql, params, String.class);
    }
}

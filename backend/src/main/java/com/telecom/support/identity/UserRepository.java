package com.telecom.support.identity;

import com.telecom.support.identity.CurrentUser.UserType;
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

    private record UserRow(long id, String username, String passwordHash, String displayName, String userType,
                           Long customerId, Long teamId, String status) {}

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

    private final NamedParameterJdbcTemplate jdbc;

    public UserRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The user with roles and permissions, or empty for an unknown username. */
    public Optional<CurrentUser> findByUsername(String username) {
        return jdbc.query(FIND_BY_USERNAME, Map.of("username", username), (rs, i) -> new UserRow(
                        rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"),
                        rs.getString("display_name"), rs.getString("user_type"),
                        rs.getObject("customer_id", Long.class), rs.getObject("team_id", Long.class),
                        rs.getString("status")))
                .stream()
                .findFirst()
                .map(this::toCurrentUser);
    }

    private CurrentUser toCurrentUser(UserRow row) {
        Map<String, Long> params = Map.of("userId", row.id());
        Set<String> roles = new TreeSet<>(codes(FIND_ROLES, params));
        Set<String> permissions = new TreeSet<>(codes(FIND_PERMISSIONS, params));
        return new CurrentUser(row.id(), row.username(), row.passwordHash(), row.displayName(),
                UserType.valueOf(row.userType()), row.customerId(), row.teamId(), "ACTIVE".equals(row.status()),
                roles, permissions);
    }

    private List<String> codes(String sql, Map<String, Long> params) {
        return jdbc.queryForList(sql, params, String.class);
    }
}

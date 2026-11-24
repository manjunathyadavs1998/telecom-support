package com.telecom.support.security;

import com.telecom.support.common.jdbc.JdbcUtils;
import com.telecom.support.security.AppUser.UserType;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    private record UserRow(long id, String username, String passwordHash, String displayName, String userType,
                           Long customerId, Long teamId, String status, Instant lockedUntil) {}

    private static final String FIND_BY_USERNAME = """
            SELECT id, username, password_hash, display_name, user_type, customer_id, team_id, status, locked_until
            FROM users WHERE username = :username
            """;

    private static final String FIND_ROLES_AND_PERMISSIONS = """
            SELECT r.code AS role_code, p.code AS permission_code
            FROM user_roles ur
            JOIN roles r ON r.id = ur.role_id
            LEFT JOIN role_permissions rp ON rp.role_id = r.id
            LEFT JOIN permissions p ON p.id = rp.permission_id
            WHERE ur.user_id = :userId
            """;

    private static final String LOGIN_SUCCEEDED = """
            UPDATE users SET failed_logins = 0, locked_until = NULL, last_login_at = :now WHERE id = :id
            """;

    /** Increments the counter and locks the account for {@code lockMinutes} once it reaches {@code maxFailures}. */
    private static final String LOGIN_FAILED = """
            UPDATE users
            SET failed_logins = failed_logins + 1,
                locked_until = CASE WHEN failed_logins + 1 >= :maxFailures
                                    THEN DATE_ADD(:now, INTERVAL :lockMinutes MINUTE) ELSE locked_until END
            WHERE username = :username
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public UserRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AppUser> findByUsername(String username) {
        return jdbc.query(FIND_BY_USERNAME, Map.of("username", username), (rs, i) -> new UserRow(
                        rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"),
                        rs.getString("display_name"), rs.getString("user_type"),
                        JdbcUtils.nullableLong(rs, "customer_id"), JdbcUtils.nullableLong(rs, "team_id"),
                        rs.getString("status"), JdbcUtils.instant(rs, "locked_until")))
                .stream().findFirst()
                .map(this::toAppUser);
    }

    public void loginSucceeded(long userId, Instant now) {
        jdbc.update(LOGIN_SUCCEEDED, Map.of("id", userId, "now", JdbcUtils.ts(now)));
    }

    public void loginFailed(String username, Instant now, int maxFailures, int lockMinutes) {
        jdbc.update(LOGIN_FAILED, new MapSqlParameterSource()
                .addValue("username", username)
                .addValue("now", JdbcUtils.ts(now))
                .addValue("maxFailures", maxFailures)
                .addValue("lockMinutes", lockMinutes));
    }

    private AppUser toAppUser(UserRow row) {
        Set<String> roles = new HashSet<>();
        Set<String> permissions = new HashSet<>();
        jdbc.query(FIND_ROLES_AND_PERMISSIONS, Map.of("userId", row.id()), rs -> {
            roles.add(rs.getString("role_code"));
            String permission = rs.getString("permission_code");
            if (permission != null) {
                permissions.add(permission);
            }
        });
        return new AppUser(row.id(), row.username(), row.passwordHash(), row.displayName(),
                UserType.valueOf(row.userType()), row.customerId(), row.teamId(), roles, permissions,
                "ACTIVE".equals(row.status()), row.lockedUntil());
    }
}

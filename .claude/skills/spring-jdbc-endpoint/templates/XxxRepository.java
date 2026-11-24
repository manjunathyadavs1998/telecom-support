package com.telecom.support.xxx;

import com.telecom.support.common.jdbc.JdbcUtils;
import com.telecom.support.common.jdbc.SortWhitelist;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class XxxRepository {

    public record XxxRow(long id, long customerId, String name, String status, Instant createdAt, int version) {}

    private static final RowMapper<XxxRow> MAPPER = XxxRepository::map;

    private static final SortWhitelist SORT = SortWhitelist.of("createdAt", "x.created_at", "name", "x.name");

    private static final String SELECT = """
            SELECT x.id, x.customer_id, x.name, x.status, x.created_at, x.version
            FROM xxx x
            """;

    private static final String INSERT = """
            INSERT INTO xxx (customer_id, name, status, created_at, version)
            VALUES (:customerId, :name, :status, :createdAt, 0)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public XxxRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<XxxRow> findByIdForCustomer(long id, long customerId) {
        return jdbc.query(SELECT + " WHERE x.id = :id AND x.customer_id = :customerId",
                        Map.of("id", id, "customerId", customerId), MAPPER)
                .stream().findFirst();
    }

    public List<XxxRow> search(long customerId, String status, Instant from, Instant to, String sort, int limit, int offset) {
        var sql = new StringBuilder(SELECT).append(" WHERE x.customer_id = :customerId AND x.created_at >= :from AND x.created_at < :to");
        var params = new MapSqlParameterSource("customerId", customerId).addValue("from", JdbcUtils.ts(from)).addValue("to", JdbcUtils.ts(to));
        if (status != null) {
            sql.append(" AND x.status = :status");
            params.addValue("status", status);
        }
        sql.append(" ORDER BY ").append(SORT.orderBy(sort, "createdAt,desc")).append(" LIMIT :limit OFFSET :offset");
        params.addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(sql.toString(), params, MAPPER);
    }

    public long insert(long customerId, String name, String status, Instant now) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(INSERT, new MapSqlParameterSource()
                .addValue("customerId", customerId)
                .addValue("name", name)
                .addValue("status", status)
                .addValue("createdAt", JdbcUtils.ts(now)), keys, new String[] {"id"});
        return JdbcUtils.key(keys);
    }

    private static XxxRow map(ResultSet rs, int i) throws SQLException {
        return new XxxRow(rs.getLong("id"), rs.getLong("customer_id"), rs.getString("name"),
                rs.getString("status"), JdbcUtils.instant(rs, "created_at"), rs.getInt("version"));
    }
}

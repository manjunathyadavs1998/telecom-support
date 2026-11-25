package com.telecom.support.servicecase;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code case_activity} (design 02-customer backend §2, R10; BR-04): append-only case history, each row PUBLIC
 * (customer-visible) or INTERNAL. Slice 02 only writes the CREATED row of a new case; DB triggers reject UPDATE and
 * DELETE.
 */
@Repository
class CaseActivityRepository {

    private static final String INSERT_CREATED = """
            INSERT INTO case_activity (case_id, activity_type, visibility, actor_user_id, old_value, new_value,
                                       note_text, created_at)
            VALUES (:caseId, 'CREATED', 'PUBLIC', :actorUserId, NULL, 'CREATED', NULL, :now)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    CaseActivityRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The one customer-visible CREATED row of a new case (R10). */
    void insertCreated(long caseId, long actorUserId, Instant now) {
        jdbc.update(INSERT_CREATED, new MapSqlParameterSource()
                .addValue("caseId", caseId)
                .addValue("actorUserId", actorUserId)
                .addValue("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC)));
    }
}

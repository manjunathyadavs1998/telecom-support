package com.telecom.support.servicecase;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code case_activity} (design 02-customer backend §2, R10; 03-inbound backend R9, R12; BR-04): append-only case
 * history, each row PUBLIC (customer-visible) or INTERNAL. Writes the CREATED row of a new case and an agent's INTERNAL
 * note; DB triggers reject UPDATE and DELETE.
 */
@Repository
class CaseActivityRepository {

    /** One timeline entry; {@code actorUserId} null for system rows. */
    record ActivityRow(String activityType, String visibility, Long actorUserId, String oldValue, String newValue,
                       String noteText, Instant createdAt) {}

    private static final String INSERT_CREATED = """
            INSERT INTO case_activity (case_id, activity_type, visibility, actor_user_id, old_value, new_value,
                                       note_text, created_at)
            VALUES (:caseId, 'CREATED', 'PUBLIC', :actorUserId, NULL, 'CREATED', NULL, :now)
            """;

    private static final String INSERT_INTERNAL_NOTE = """
            INSERT INTO case_activity (case_id, activity_type, visibility, actor_user_id, old_value, new_value,
                                       note_text, created_at)
            VALUES (:caseId, 'NOTE', 'INTERNAL', :actorUserId, NULL, NULL, :note, :now)
            """;

    private static final String FIND_TIMELINE = """
            SELECT activity_type, visibility, actor_user_id, old_value, new_value, note_text, created_at
            FROM case_activity
            WHERE case_id = :caseId
            ORDER BY created_at ASC, id ASC
            """;

    private static final String FIND_PUBLIC_TIMELINE = """
            SELECT activity_type, visibility, actor_user_id, old_value, new_value, note_text, created_at
            FROM case_activity
            WHERE case_id = :caseId AND visibility = 'PUBLIC'
            ORDER BY created_at ASC, id ASC
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

    /** An agent's note on a new case, never customer-visible (03 R12, BR-04). */
    void insertInternalNote(long caseId, long actorUserId, String note, Instant now) {
        jdbc.update(INSERT_INTERNAL_NOTE, new MapSqlParameterSource()
                .addValue("caseId", caseId)
                .addValue("actorUserId", actorUserId)
                .addValue("note", note)
                .addValue("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC)));
    }

    /** The case's timeline oldest first; INTERNAL rows only when {@code includeInternal} (03 R9, BR-04). */
    List<ActivityRow> findTimeline(long caseId, boolean includeInternal) {
        return jdbc.query(includeInternal ? FIND_TIMELINE : FIND_PUBLIC_TIMELINE, Map.of("caseId", caseId),
                (rs, i) -> new ActivityRow(rs.getString("activity_type"), rs.getString("visibility"),
                        rs.getObject("actor_user_id", Long.class), rs.getString("old_value"),
                        rs.getString("new_value"), rs.getString("note_text"),
                        rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)));
    }
    private static final String INSERT_ROW = """
            INSERT INTO case_activity (case_id, activity_type, visibility, actor_user_id, old_value, new_value,
                                       note_text, created_at)
            VALUES (:caseId, :type, :visibility, :actorUserId, :oldValue, :newValue, :note, :now)
            """;

    /** R5/R6: ASSIGNED, INTERNAL, old/new user id ({@code note} = the reassign reason). */
    void insertAssigned(long caseId, long actorUserId, Long fromUserId, long toUserId, String note, Instant now) {
        insert(caseId, "ASSIGNED", "INTERNAL", actorUserId, fromUserId == null ? null : String.valueOf(fromUserId),
                String.valueOf(toUserId), note, now);
    }

    /** R5–R9: STATUS_CHANGED, PUBLIC (customers see the public mapping); actor null for the auto-close job. */
    void insertStatusChanged(long caseId, Long actorUserId, String from, String to, Instant now) {
        insert(caseId, "STATUS_CHANGED", "PUBLIC", actorUserId, from, to, null, now);
    }

    /** R7 remarks and R10 notes: NOTE with the given visibility. */
    void insertNote(long caseId, long actorUserId, String visibility, String text, Instant now) {
        insert(caseId, "NOTE", visibility, actorUserId, null, null, text, now);
    }

    private void insert(long caseId, String type, String visibility, Long actorUserId, String oldValue,
                        String newValue, String note, Instant now) {
        jdbc.update(INSERT_ROW, new MapSqlParameterSource()
                .addValue("caseId", caseId).addValue("type", type).addValue("visibility", visibility)
                .addValue("actorUserId", actorUserId).addValue("oldValue", oldValue).addValue("newValue", newValue)
                .addValue("note", note).addValue("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC)));
    }
}

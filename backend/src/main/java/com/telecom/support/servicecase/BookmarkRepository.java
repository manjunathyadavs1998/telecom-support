package com.telecom.support.servicecase;

import com.telecom.support.servicecase.QueueRepository.QueueRow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code bookmarks} (design 04-outbound backend §2, R11, R12; OUT-06): personal, keyed by the actor id from the principal,
 * not audited. {@code changed = updated_at > last_seen_at} (OUT-06.4).
 */
@Repository
class BookmarkRepository {

    /** A case as R11 needs it: id and assignment (for scope S). */
    record CaseRef(long id, Long assignedUserId, Long assignedTeamId) {}

    /** One O12 row: the bookmark plus the case's R3 fields. */
    record BookmarkRow(QueueRow row, Instant bookmarkedAt, Instant lastSeenAt) {}

    private static final String FIND_CASE = """
            SELECT id, assigned_user_id, assigned_team_id FROM cases WHERE reference = :reference
            """;

    private static final String COUNT = """
            SELECT COUNT(*) FROM bookmarks WHERE user_id = :userId
            """;

    private static final String EXISTS = """
            SELECT COUNT(*) FROM bookmarks WHERE user_id = :userId AND case_id = :caseId
            """;

    /** R11: idempotent; a repeat PUT only refreshes {@code last_seen_at}. */
    private static final String UPSERT = """
            INSERT INTO bookmarks (user_id, case_id, last_seen_at, created_at)
            VALUES (:userId, :caseId, :now, :now) AS new
            ON DUPLICATE KEY UPDATE last_seen_at = new.last_seen_at
            """;

    private static final String DELETE = """
            DELETE FROM bookmarks WHERE user_id = :userId AND case_id = :caseId
            """;

    private static final String MARK_SEEN = """
            UPDATE bookmarks SET last_seen_at = :now WHERE user_id = :userId AND case_id = :caseId
            """;

    private static final String PAGE = """
            SELECT c.id, c.reference, c.account_id, c.severity, c.status, c.assigned_user_id, c.assigned_team_id,
                   c.contact_msisdn, c.created_at, c.updated_at, c.sla_due_at,
                   COALESCE(pc.name, cat.name) AS category_name,
                   CASE WHEN pc.id IS NULL THEN NULL ELSE cat.name END AS subcategory_name,
                   TRUE AS bookmarked, b.created_at AS bookmarked_at, b.last_seen_at
            FROM bookmarks b
            JOIN cases c ON c.id = b.case_id
            JOIN categories cat ON cat.id = c.category_id
            LEFT JOIN categories pc ON pc.id = cat.parent_id
            WHERE b.user_id = :userId
            ORDER BY b.created_at DESC, b.case_id DESC
            LIMIT :limit OFFSET :offset
            """;

    private static final String CHANGED_COUNT = """
            SELECT COUNT(*)
            FROM bookmarks b
            JOIN cases c ON c.id = b.case_id
            WHERE b.user_id = :userId AND c.updated_at > b.last_seen_at
              AND """ + OutboundScope.SQL;

    private final NamedParameterJdbcTemplate jdbc;

    BookmarkRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<CaseRef> findCase(String reference) {
        return jdbc.query(FIND_CASE, Map.of("reference", reference), (rs, i) -> new CaseRef(rs.getLong("id"),
                rs.getObject("assigned_user_id", Long.class), rs.getObject("assigned_team_id", Long.class)))
                .stream().findFirst();
    }

    long count(long userId) {
        Long n = jdbc.queryForObject(COUNT, Map.of("userId", userId), Long.class);
        return n == null ? 0 : n;
    }

    boolean exists(long userId, long caseId) {
        Long n = jdbc.queryForObject(EXISTS, Map.of("userId", userId, "caseId", caseId), Long.class);
        return n != null && n > 0;
    }

    void upsert(long userId, long caseId, Instant now) {
        jdbc.update(UPSERT, Map.of("userId", userId, "caseId", caseId, "now", QueueRepository.utc(now)));
    }

    void delete(long userId, long caseId) {
        jdbc.update(DELETE, Map.of("userId", userId, "caseId", caseId));
    }

    /** R12: the actor has seen the case now; false when it is not bookmarked. */
    boolean markSeen(long userId, long caseId, Instant now) {
        return jdbc.update(MARK_SEEN, Map.of("userId", userId, "caseId", caseId, "now", QueueRepository.utc(now))) == 1;
    }

    List<BookmarkRow> page(long userId, int limit, long offset) {
        return jdbc.query(PAGE, Map.of("userId", userId, "limit", limit, "offset", offset), (rs, i) -> new BookmarkRow(
                new QueueRow(rs.getLong("id"), rs.getString("reference"), rs.getLong("account_id"),
                        rs.getString("category_name"), rs.getString("subcategory_name"), rs.getString("severity"),
                        rs.getString("status"), rs.getObject("assigned_user_id", Long.class),
                        rs.getObject("assigned_team_id", Long.class), rs.getString("contact_msisdn"),
                        QueueRepository.instant(rs, "created_at"), QueueRepository.instant(rs, "updated_at"),
                        QueueRepository.instant(rs, "sla_due_at"), true),
                QueueRepository.instant(rs, "bookmarked_at"), QueueRepository.instant(rs, "last_seen_at")));
    }

    /** R12: changed bookmarks whose case is still in the actor's scope S (as the rows' {@code changed}). */
    long changedCount(long userId, OutboundScope scope) {
        Map<String, Object> params = new java.util.HashMap<>(scope.params());
        params.put("userId", userId);
        Long n = jdbc.queryForObject(CHANGED_COUNT, params, Long.class);
        return n == null ? 0 : n;
    }
}

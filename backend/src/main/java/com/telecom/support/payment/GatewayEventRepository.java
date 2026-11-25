package com.telecom.support.payment;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code payment_gateway_events}: one row per verified webhook event (design 02-customer backend §2, §3.1, §8;
 * CUS-08.3/08.4, NFR-INTEG). The unique {@code gateway_event_id} is the webhook dedupe: a second delivery of the same
 * event cannot insert, so it changes nothing. The row is written in the transaction of the transition it records, so a
 * transition that rolls back also forgets the event and the gateway's retry is processed again.
 */
@Repository
class GatewayEventRepository {

    /** {@code payment_gateway_events.outcome}. */
    enum Outcome { APPLIED, IGNORED, REJECTED }

    /**
     * Inserted before the payment row is locked, so it does not read {@code payments} (a shared lock taken here would
     * deadlock two events of one payment when both upgrade to the transition's exclusive lock).
     */
    private static final String INSERT = """
            INSERT INTO payment_gateway_events (gateway_event_id, payment_id, event_type, outcome, created_at)
            VALUES (:eventId, NULL, :eventType, :outcome, :now)
            """;

    private static final String SET_OUTCOME = """
            UPDATE payment_gateway_events
            SET outcome = :outcome,
                payment_id = :paymentId
            WHERE gateway_event_id = :eventId
            """;

    /** Plain (non-locking) read; the transition has already locked the row when it exists. */
    private static final String PAYMENT_ID = """
            SELECT id FROM payments WHERE reference = :reference
            """;

    private final NamedParameterJdbcTemplate jdbc;

    GatewayEventRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records a newly received event with a provisional outcome. Must run in the caller's transaction; a concurrent
     * delivery of the same id waits on the unique key until that transaction ends.
     *
     * @return false when the event id was already recorded (duplicate delivery: nothing else may happen)
     */
    boolean insertIfNew(String eventId, String eventType, Outcome outcome, Instant now) {
        try {
            jdbc.update(INSERT, new MapSqlParameterSource()
                    .addValue("eventId", eventId)
                    .addValue("eventType", eventType)
                    .addValue("outcome", outcome.name())
                    .addValue("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC)));
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    /**
     * Sets the final outcome of an event recorded in this transaction and links it to the payment with
     * {@code paymentReference} (none when no payment has it).
     */
    void complete(String eventId, String paymentReference, Outcome outcome) {
        Long paymentId = jdbc.query(PAYMENT_ID, new MapSqlParameterSource("reference", paymentReference),
                (rs, i) -> rs.getLong("id")).stream().findFirst().orElse(null);
        jdbc.update(SET_OUTCOME, new MapSqlParameterSource()
                .addValue("eventId", eventId)
                .addValue("paymentId", paymentId)
                .addValue("outcome", outcome.name()));
    }
}

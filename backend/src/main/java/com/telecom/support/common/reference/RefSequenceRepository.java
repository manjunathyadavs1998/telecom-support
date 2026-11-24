package com.telecom.support.common.reference;

import java.time.LocalDate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** SQL for {@code ref_sequences} (01 backend §2, §6.2). One row per prefix and Asia/Kolkata day. */
@Repository
class RefSequenceRepository {

    /*
     * The first row of a day takes the INSERT path: LAST_INSERT_ID(1) both stores 1 and sets the connection's
     * LAST_INSERT_ID to 1 (the table has no AUTO_INCREMENT column, so the insert does not overwrite it). Every later
     * call takes the UPDATE path, which atomically increments under the row lock and sets LAST_INSERT_ID to the new value.
     */
    private static final String INCREMENT = """
            INSERT INTO ref_sequences (seq_name, seq_date, next_value)
            VALUES (:name, :day, LAST_INSERT_ID(1))
            ON DUPLICATE KEY UPDATE next_value = LAST_INSERT_ID(next_value + 1)
            """;
    private static final String LAST_VALUE = "SELECT LAST_INSERT_ID()";

    private final NamedParameterJdbcTemplate jdbc;

    RefSequenceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Issues the next value of (name, day). Must run inside a transaction so both statements use the same connection,
     * because {@code LAST_INSERT_ID()} is per connection.
     */
    long next(String name, LocalDate day) {
        jdbc.update(INCREMENT, new MapSqlParameterSource().addValue("name", name).addValue("day", day));
        Long value = jdbc.getJdbcOperations().queryForObject(LAST_VALUE, Long.class);
        if (value == null || value < 1) {
            throw new IllegalStateException("ref_sequences returned no value for " + name);
        }
        return value;
    }
}

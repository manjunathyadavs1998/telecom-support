package com.telecom.support.common.jdbc;

import com.telecom.support.common.error.Errors.BadRequestException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps public sort keys (e.g. {@code createdAt,desc}) to SQL column expressions from a fixed whitelist so user
 * input never reaches the ORDER BY clause (ADR 0002).
 */
public final class SortWhitelist {

    private final Map<String, String> columns;

    private SortWhitelist(Map<String, String> columns) {
        this.columns = columns;
    }

    /** Pairs of (publicKey, sqlColumn). */
    public static SortWhitelist of(String... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("pairs must be key/column");
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return new SortWhitelist(Map.copyOf(map));
    }

    /** Returns e.g. {@code c.created_at DESC, id DESC}; id tiebreaker keeps pagination stable. */
    public String orderBy(String requested, String fallback) {
        String value = requested == null || requested.isBlank() ? fallback : requested;
        String[] parts = value.split(",");
        String column = columns.get(parts[0].trim());
        if (column == null) {
            throw new BadRequestException("INVALID_SORT", "Unsupported sort field: " + parts[0].trim());
        }
        String direction = parts.length > 1 && parts[1].trim().equalsIgnoreCase("asc") ? "ASC" : "DESC";
        String table = column.contains(".") ? column.substring(0, column.indexOf('.') + 1) : "";
        return column + " " + direction + ", " + table + "id " + direction;
    }
}

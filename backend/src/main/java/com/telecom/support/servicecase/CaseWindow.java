package com.telecom.support.servicecase;

import com.telecom.support.common.error.ApiExceptions.BadRequestException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * The {@code created_at} window of a case list (00-overview §3, BR-05/06; 02 R9, 03 R8): inclusive display-zone dates,
 * default today minus 3 months .. today; only {@code to} given → the 3 months before it. {@code from > to} or a range
 * over 366 days → 400. Held as a UTC half-open interval {@code [fromInclusive, toExclusive)}.
 */
record CaseWindow(Instant fromInclusive, Instant toExclusive) {

    static final int DEFAULT_WINDOW_MONTHS = 3;
    static final long MAX_WINDOW_DAYS = 366;

    static CaseWindow resolve(LocalDate from, LocalDate to, Clock clock, ZoneId zone) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : end.minusMonths(DEFAULT_WINDOW_MONTHS);
        if (start.isAfter(end)) {
            throw new BadRequestException("Case list from " + start + " after to " + end,
                    "The start date must not be after the end date.");
        }
        if (ChronoUnit.DAYS.between(start, end) > MAX_WINDOW_DAYS) {
            throw new BadRequestException("Case list range " + start + ".." + end + " over " + MAX_WINDOW_DAYS + " days",
                    "The date range can be at most 366 days.");
        }
        return new CaseWindow(start.atStartOfDay(zone).toInstant(), end.plusDays(1).atStartOfDay(zone).toInstant());
    }
}

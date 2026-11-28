package com.telecom.support.servicecase;

import com.telecom.support.common.error.ApiExceptions.BadRequestException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

/**
 * R2 validation of an O2 search (design 04-outbound backend R2; OUT-02, XC-05): each window {@code from ≤ to}, ≤ 366 days, a
 * {@code to}-only range = the 3 months before it; no range at all → created = last 3 months (OUT-02.4). Dates are
 * display-zone ({@code Asia/Kolkata}) inclusive days. Invalid input is 400 with a user-safe detail.
 */
final class QueueSearchValidator {

    static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");
    static final int MAX_PAGE_SIZE = 100;

    /** An inclusive display-zone date window. */
    record Range(LocalDate from, LocalDate to) {}

    private QueueSearchValidator() {
    }

    /**
     * One window ({@code created} or {@code updated}); null when neither bound is given. A {@code from}-only range ends
     * today.
     */
    static Range range(String name, LocalDate from, LocalDate to, Clock clock, ZoneId zone) {
        if (from == null && to == null) {
            return null;
        }
        LocalDate end = to != null ? to : LocalDate.ofInstant(clock.instant(), zone);
        LocalDate start = from != null ? from : end.minusMonths(CaseWindow.DEFAULT_WINDOW_MONTHS);
        if (start.isAfter(end)) {
            throw new BadRequestException("Queue " + name + " from " + start + " after to " + end,
                    "The start date must not be after the end date.");
        }
        if (ChronoUnit.DAYS.between(start, end) > CaseWindow.MAX_WINDOW_DAYS) {
            throw new BadRequestException("Queue " + name + " range over " + CaseWindow.MAX_WINDOW_DAYS + " days",
                    "The date range can be at most 366 days.");
        }
        return new Range(start, end);
    }

    /** The default created window (OUT-02.4): the 3 months up to today. */
    static Range defaultCreated(Clock clock, ZoneId zone) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        return new Range(today.minusMonths(CaseWindow.DEFAULT_WINDOW_MONTHS), today);
    }

    static QueueRepository.Sort sort(String value) {
        if (value == null) {
            return QueueRepository.Sort.CREATED_AT_DESC;
        }
        for (QueueRepository.Sort sort : QueueRepository.Sort.values()) {
            if (sort.param.equals(value)) {
                return sort;
            }
        }
        throw new BadRequestException("Unsupported queue sort " + value,
                "Sort must be createdAt, updatedAt, slaDueAt or severity, asc or desc.");
    }

    static void page(Integer page, Integer size) {
        if ((page != null && page < 0) || (size != null && (size < 1 || size > MAX_PAGE_SIZE))) {
            throw new BadRequestException("Queue page " + page + " size " + size,
                    "Page must be 0 or more and size between 1 and 100.");
        }
    }

    static void assignee(String assignee, Long assigneeUserId) {
        if (!java.util.Set.of("ANY", "ME", "UNASSIGNED", "USER").contains(assignee)) {
            throw new BadRequestException("Queue assignee " + assignee, "Assignee must be ANY, ME, UNASSIGNED or USER.");
        }
        if ("USER".equals(assignee) && assigneeUserId == null) {
            throw new BadRequestException("Queue assignee USER without assigneeUserId", "Choose the assignee.");
        }
    }

    /** The mobile filter must be an exact E.164 number (never logged). */
    static void msisdn(String msisdn) {
        if (msisdn != null && !E164.matcher(msisdn).matches()) {
            throw new BadRequestException("Queue mobile filter is not E.164", "Enter the full mobile number with +91.");
        }
    }
}

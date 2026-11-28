package com.telecom.support.servicecase;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound workspace settings {@code app.outbound.*} (design 04-outbound backend §6 config, R8, R9, R11, R12, §3.2;
 * OUT-05, OUT-06). An unset property keeps its default; an invalid value fails startup.
 *
 * @param verificationWindow   RESOLVED → CLOSED {@code WINDOW_ELAPSED} only after this (default 3d, OQ 4)
 * @param reopenWindow         CLOSED → REOPENED only within this (default 7d, OQ 5)
 * @param bookmarkLimit        bookmarks per agent (default 200, R11)
 * @param bookmarkNotifications in-app "changed since seen" indicator (default enabled, OQ 8)
 * @param autoCloseCron        {@code CaseAutoCloseJob} schedule; {@code "-"} disables it (tests only; refused in prod)
 * @param autoClose            job page size and run budget
 */
@ConfigurationProperties("app.outbound")
public record OutboundProperties(Duration verificationWindow, Duration reopenWindow, Integer bookmarkLimit,
                                 BookmarkNotifications bookmarkNotifications, String autoCloseCron,
                                 AutoClose autoClose) {

    static final Duration DEFAULT_VERIFICATION_WINDOW = Duration.ofDays(3);
    static final Duration DEFAULT_REOPEN_WINDOW = Duration.ofDays(7);
    static final int DEFAULT_BOOKMARK_LIMIT = 200;
    static final String DEFAULT_AUTO_CLOSE_CRON = "0 */5 * * * *";

    /** @param enabled whether bookmark rows carry {@code changed} (OUT-06.4) */
    public record BookmarkNotifications(Boolean enabled) {
        public BookmarkNotifications {
            enabled = enabled == null || enabled;
        }
    }

    /**
     * @param pageSize  due RESOLVED rows taken per keyset page (default 100)
     * @param runBudget a run stops taking new pages after this (default 50s)
     */
    public record AutoClose(Integer pageSize, Duration runBudget) {
        public AutoClose {
            pageSize = pageSize == null ? 100 : pageSize;
            runBudget = runBudget == null ? Duration.ofSeconds(50) : runBudget;
            if (pageSize < 1 || runBudget.isNegative() || runBudget.isZero()) {
                throw new IllegalArgumentException("app.outbound.auto-close.page-size and run-budget must be positive");
            }
        }
    }

    public OutboundProperties {
        verificationWindow = positive("verification-window", verificationWindow == null ? DEFAULT_VERIFICATION_WINDOW : verificationWindow);
        reopenWindow = positive("reopen-window", reopenWindow == null ? DEFAULT_REOPEN_WINDOW : reopenWindow);
        bookmarkLimit = bookmarkLimit == null ? DEFAULT_BOOKMARK_LIMIT : bookmarkLimit;
        if (bookmarkLimit < 1) {
            throw new IllegalArgumentException("app.outbound.bookmark-limit must be at least 1");
        }
        bookmarkNotifications = bookmarkNotifications == null ? new BookmarkNotifications(true) : bookmarkNotifications;
        autoCloseCron = autoCloseCron == null || autoCloseCron.isBlank() ? DEFAULT_AUTO_CLOSE_CRON : autoCloseCron;
        autoClose = autoClose == null ? new AutoClose(null, null) : autoClose;
    }

    private static Duration positive(String name, Duration value) {
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("app.outbound." + name + " must be positive");
        }
        return value;
    }
}

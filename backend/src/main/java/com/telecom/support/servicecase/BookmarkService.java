package com.telecom.support.servicecase;

import com.telecom.support.common.error.ApiExceptions.BadRequestException;
import com.telecom.support.common.error.ApiExceptions.NotFoundException;
import com.telecom.support.identity.CurrentUser;
import com.telecom.support.identity.UserDirectory;
import com.telecom.support.servicecase.BookmarkRepository.BookmarkRow;
import com.telecom.support.servicecase.BookmarkRepository.CaseRef;
import com.telecom.support.servicecase.CaseRuleException.Reason;
import com.telecom.support.servicecase.OutboundCaseQueryService.OutboundCaseSummary;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Personal bookmarks O10–O13 (design 04-outbound backend R11, R12; OUT-06). Keyed by the actor id from the principal only;
 * not audited. The per-agent limit is counted under the actor's {@code users} row lock (race-safe as 03 R14); no
 * transaction here also holds a case lock, so there is no lock cycle with {@link CaseCommandService}.
 */
@Service
public class BookmarkService {

    /** The {@code Bookmark} schema; {@code case} is null when the case left the actor's scope ({@code accessible=false}). */
    public record Bookmark(String reference, Instant bookmarkedAt, boolean accessible, boolean changed,
                           OutboundCaseSummary caseSummary) {

        /** JSON name {@code case} (a Java keyword). */
        @com.fasterxml.jackson.annotation.JsonProperty("case")
        public OutboundCaseSummary caseSummary() {
            return caseSummary;
        }
    }

    /** The {@code BookmarkPage} schema. */
    public record BookmarkPage(List<Bookmark> items, int page, int size, long totalItems, int totalPages,
                               long changedCount) {}

    private final BookmarkRepository bookmarks;
    private final OutboundCaseQueryService queries;
    private final UserDirectory users;
    private final OutboundProperties properties;
    private final Clock clock;

    BookmarkService(BookmarkRepository bookmarks, OutboundCaseQueryService queries, UserDirectory users,
                    OutboundProperties properties, Clock clock) {
        this.bookmarks = bookmarks;
        this.queries = queries;
        this.users = users;
        this.properties = properties;
        this.clock = clock;
    }

    /** O10 (R11): the case must be in S (else 404); idempotent; at most {@code bookmark-limit} per agent (422). */
    @Transactional
    public void add(String reference) {
        CurrentUser actor = CurrentUser.require();
        OutboundScope scope = OutboundScope.of(actor);
        CaseRef ref = bookmarks.findCase(reference)
                .filter(c -> scope.covers(c.assignedUserId(), c.assignedTeamId()))
                .orElseThrow(() -> new NotFoundException("Outbound case " + reference + " not in scope"));
        users.lockForUpdate(actor.id());
        if (!bookmarks.exists(actor.id(), ref.id()) && bookmarks.count(actor.id()) >= properties.bookmarkLimit()) {
            throw new CaseRuleException(Reason.BOOKMARK_LIMIT, "Bookmark " + reference + " over the limit");
        }
        bookmarks.upsert(actor.id(), ref.id(), clock.instant());
    }

    /** O11 (R11): removes the actor's own row; absent is fine (204); an unknown reference is 404. No scope check. */
    @Transactional
    public void remove(String reference) {
        CurrentUser actor = CurrentUser.require();
        CaseRef ref = bookmarks.findCase(reference)
                .orElseThrow(() -> new NotFoundException("Case " + reference + " not found"));
        bookmarks.delete(actor.id(), ref.id());
    }

    /** O13 (R12): the actor saw the case now; 404 when it is not bookmarked. */
    @Transactional
    public void seen(String reference) {
        CurrentUser actor = CurrentUser.require();
        CaseRef ref = bookmarks.findCase(reference)
                .orElseThrow(() -> new NotFoundException("Case " + reference + " not found"));
        if (!bookmarks.markSeen(actor.id(), ref.id(), clock.instant())) {
            throw new NotFoundException("Case " + reference + " is not bookmarked");
        }
    }

    /** O12 (R12): newest first; cases out of S show the reference only. */
    @Transactional(readOnly = true)
    public BookmarkPage list(Integer page, Integer size) {
        int p = page == null ? 0 : page;
        int s = size == null ? OutboundCaseQueryService.DEFAULT_PAGE_SIZE : size;
        if (p < 0 || s < 1 || s > QueueSearchValidator.MAX_PAGE_SIZE) {
            throw new BadRequestException("Bookmark page " + p + " size " + s,
                    "Page must be 0 or more and size between 1 and 100.");
        }
        CurrentUser actor = CurrentUser.require();
        OutboundScope scope = OutboundScope.of(actor);
        boolean notify = properties.bookmarkNotifications().enabled();
        long total = bookmarks.count(actor.id());
        List<BookmarkRow> rows = total == 0 ? List.of() : bookmarks.page(actor.id(), s, (long) p * s);
        List<BookmarkRow> visible = rows.stream()
                .filter(r -> scope.covers(r.row().assignedUserId(), r.row().assignedTeamId())).toList();
        List<OutboundCaseSummary> summaries = queries.summaries(visible.stream().map(BookmarkRow::row).toList(),
                clock.instant());
        List<Bookmark> items = new ArrayList<>();
        int next = 0;
        for (BookmarkRow r : rows) {
            boolean accessible = scope.covers(r.row().assignedUserId(), r.row().assignedTeamId());
            boolean changed = notify && accessible && r.row().updatedAt().isAfter(r.lastSeenAt());
            items.add(new Bookmark(r.row().reference(), r.bookmarkedAt(), accessible, changed,
                    accessible ? summaries.get(next++) : null));
        }
        return new BookmarkPage(items, p, s, total, (int) ((total + s - 1) / s),
                notify ? bookmarks.changedCount(actor.id(), scope) : 0);
    }
}

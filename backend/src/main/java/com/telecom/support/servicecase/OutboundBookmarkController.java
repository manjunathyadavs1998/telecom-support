package com.telecom.support.servicecase;

import com.telecom.support.common.error.ProblemDetailFactory;
import com.telecom.support.servicecase.BookmarkService.BookmarkPage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Bookmarks O10–O13 (design 04-outbound backend §5, R11, R12; OUT-06): {@code case.read.queue} + {@code case.bookmark}. */
@RestController
@RequestMapping("/api/v1/outbound")
public class OutboundBookmarkController {

    static final String BOOKMARK = OutboundCaseController.READ_QUEUE
            + " and hasAuthority(T(com.telecom.support.identity.Permissions).CASE_BOOKMARK)";

    private final BookmarkService bookmarks;
    private final ProblemDetailFactory problems;

    OutboundBookmarkController(BookmarkService bookmarks, ProblemDetailFactory problems) {
        this.bookmarks = bookmarks;
        this.problems = problems;
    }

    /** O10 (R11). */
    @PutMapping("/cases/{reference}/bookmark")
    @PreAuthorize(BOOKMARK)
    public ResponseEntity<Void> add(@PathVariable String reference) {
        bookmarks.add(reference);
        return ResponseEntity.noContent().build();
    }

    /** O11 (R11). */
    @DeleteMapping("/cases/{reference}/bookmark")
    @PreAuthorize(BOOKMARK)
    public ResponseEntity<Void> remove(@PathVariable String reference) {
        bookmarks.remove(reference);
        return ResponseEntity.noContent().build();
    }

    /** O12 (R12). */
    @GetMapping("/bookmarks")
    @PreAuthorize(BOOKMARK)
    public BookmarkPage list(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return bookmarks.list(page, size);
    }

    /** O13 (R12). */
    @PostMapping("/bookmarks/{reference}/seen")
    @PreAuthorize(BOOKMARK)
    public ResponseEntity<Void> seen(@PathVariable String reference) {
        bookmarks.seen(reference);
        return ResponseEntity.noContent().build();
    }

    /** 422 {@code CaseProblem} ({@code bookmark_limit}). */
    @ExceptionHandler(CaseRuleException.class)
    ResponseEntity<ProblemDetail> caseRule(CaseRuleException ex, HttpServletRequest request) {
        ProblemDetail pd = problems.create(ex.problem(), request.getRequestURI());
        ex.userDetail().ifPresent(pd::setDetail);
        pd.setProperty("reason", ex.reason().value());
        return ResponseEntity.status(pd.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }
}

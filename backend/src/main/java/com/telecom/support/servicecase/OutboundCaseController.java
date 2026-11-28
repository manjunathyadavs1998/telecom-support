package com.telecom.support.servicecase;

import com.telecom.support.common.error.ProblemDetailFactory;
import com.telecom.support.servicecase.OutboundCaseQueryService.OutboundCaseDetail;
import com.telecom.support.servicecase.OutboundCaseQueryService.OutboundCasePage;
import com.telecom.support.servicecase.OutboundCaseQueryService.QueueAgentView;
import com.telecom.support.servicecase.OutboundCaseQueryService.QueueCategory;
import com.telecom.support.servicecase.OutboundCaseQueryService.QueueSearch;
import com.telecom.support.servicecase.OutboundCaseQueryService.QueueSummary;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Outbound workspace endpoints O1–O9 (design 04-outbound backend §5; OUT-01..OUT-05). Every handler needs
 * {@code case.read.queue} plus its own permission (§4); scope S and ownership are enforced in the services.
 */
@RestController
@RequestMapping("/api/v1/outbound")
public class OutboundCaseController {

    static final String READ_QUEUE = "hasAuthority(T(com.telecom.support.identity.Permissions).CASE_READ_QUEUE)";

    private final OutboundCaseQueryService queries;
    private final ProblemDetailFactory problems;

    OutboundCaseController(OutboundCaseQueryService queries, ProblemDetailFactory problems) {
        this.queries = queries;
        this.problems = problems;
    }

    /** O1 (R1). */
    @GetMapping("/case-summary")
    @PreAuthorize(READ_QUEUE)
    public QueueSummary summary(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return queries.summary(from, to);
    }

    /** O2 (R2): POST so the mobile number stays out of URLs and access logs. */
    @PostMapping("/case-searches")
    @PreAuthorize(READ_QUEUE)
    public OutboundCasePage search(@RequestBody(required = false) QueueSearch search) {
        return queries.search(search);
    }

    /** O3 (R13). */
    @GetMapping("/case-categories")
    @PreAuthorize(READ_QUEUE)
    public List<QueueCategory> categories() {
        return queries.categories();
    }

    /** O4 (R13). */
    @GetMapping("/agents")
    @PreAuthorize(READ_QUEUE)
    public List<QueueAgentView> agents() {
        return queries.agents();
    }

    /** O5 (R4). */
    @GetMapping("/cases/{reference}")
    @PreAuthorize(READ_QUEUE)
    public OutboundCaseDetail detail(@PathVariable String reference) {
        return queries.detail(reference);
    }

    /** 409 / 422 {@code CaseProblem}: the problem row of the reason plus {@code reason}. */
    @ExceptionHandler(CaseRuleException.class)
    ResponseEntity<ProblemDetail> caseRule(CaseRuleException ex, HttpServletRequest request) {
        ProblemDetail pd = problems.create(ex.problem(), request.getRequestURI());
        ex.userDetail().ifPresent(pd::setDetail);
        pd.setProperty("reason", ex.reason().value());
        return ResponseEntity.status(pd.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }
}

package com.telecom.support.servicecase;

import com.telecom.support.common.error.ProblemDetailFactory;
import com.telecom.support.common.error.ProblemDetailFactory.FieldViolation;
import com.telecom.support.common.idempotency.IdempotencyService;
import com.telecom.support.identity.CurrentUser;
import com.telecom.support.servicecase.AgentCaseQueryService.CaseStatus;
import com.telecom.support.servicecase.CaseCommandService.CaseFieldException;
import com.telecom.support.servicecase.CaseLifecycle.ClosureType;
import com.telecom.support.servicecase.OutboundCaseQueryService.OutboundCaseDetail;
import com.telecom.support.servicecase.OutboundCaseQueryService.OutboundCasePage;
import com.telecom.support.servicecase.OutboundCaseQueryService.QueueAgentView;
import com.telecom.support.servicecase.OutboundCaseQueryService.QueueCategory;
import com.telecom.support.servicecase.OutboundCaseQueryService.QueueSearch;
import com.telecom.support.servicecase.OutboundCaseQueryService.QueueSummary;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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

    static final String CASE_NOTE_ADD_OPERATION = "CASE_NOTE_ADD";

    /** The {@code ReassignRequest} schema (O7). */
    public record ReassignRequest(@NotNull Long assigneeUserId, @NotBlank @Size(min = 5, max = 500) String reason,
                                  @NotNull Integer version) {}

    /** The {@code StatusChangeRequest} schema (O8); remarks rules are R7 (service, 400 {@code errors[]}). */
    public record StatusChangeRequest(@NotNull CaseStatus toStatus, @Size(max = 1000) String remarks,
                                      ClosureType closureType, @NotNull Integer version) {}

    /** The {@code NoteRequest} schema (O9). */
    public record NoteRequest(@NotNull @Pattern(regexp = "PUBLIC|INTERNAL") String visibility,
                              @NotBlank @Size(max = 2000) String text) {}

    /** The canonical idempotent request of O9: the path reference and the body. */
    record KeyedNote(String reference, NoteRequest body) {}

    private final OutboundCaseQueryService queries;
    private final CaseCommandService commands;
    private final IdempotencyService idempotency;
    private final ProblemDetailFactory problems;

    OutboundCaseController(OutboundCaseQueryService queries, CaseCommandService commands,
                           IdempotencyService idempotency, ProblemDetailFactory problems) {
        this.queries = queries;
        this.commands = commands;
        this.idempotency = idempotency;
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

    /** O6 (R5): claim; retry-safe for the owner. */
    @PostMapping("/cases/{reference}/claim")
    @PreAuthorize(READ_QUEUE + " and hasAuthority(T(com.telecom.support.identity.Permissions).CASE_ASSIGN)")
    public OutboundCaseDetail claim(@PathVariable String reference) {
        return commands.claim(reference);
    }

    /** O7 (R6): reassign (supervisor). */
    @PostMapping("/cases/{reference}/assignment")
    @PreAuthorize(READ_QUEUE + " and hasAuthority(T(com.telecom.support.identity.Permissions).CASE_ASSIGN_ANY)")
    public OutboundCaseDetail reassign(@PathVariable String reference, @Valid @RequestBody ReassignRequest body) {
        return commands.reassign(reference, body.assigneeUserId(), body.reason().strip(), body.version());
    }

    /** O8 (R7–R9): status change. */
    @PostMapping("/cases/{reference}/status")
    @PreAuthorize(READ_QUEUE + " and hasAuthority(T(com.telecom.support.identity.Permissions).CASE_STATUS_UPDATE)")
    public OutboundCaseDetail changeStatus(@PathVariable String reference,
                                           @Valid @RequestBody StatusChangeRequest body) {
        return commands.changeStatus(reference, body.toStatus(), body.remarks(), body.closureType(), body.version());
    }

    /** O9 (R10): note or customer-visible comment; honours {@code Idempotency-Key} ({@code CASE_NOTE_ADD}). */
    @PostMapping("/cases/{reference}/notes")
    @PreAuthorize(READ_QUEUE)
    public ResponseEntity<?> addNote(@PathVariable String reference,
                                     @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                     @Valid @RequestBody NoteRequest body) {
        return idempotency.execute(CurrentUser.require().id(), CASE_NOTE_ADD_OPERATION, idempotencyKey,
                new KeyedNote(reference, body),
                () -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(commands.addNote(reference, body.visibility(), body.text())));
    }

    /** 400 with one {@code errors[]} entry (R7 remarks / closure type, R10 text). */
    @ExceptionHandler(CaseFieldException.class)
    ResponseEntity<ProblemDetail> caseField(CaseFieldException ex, HttpServletRequest request) {
        ProblemDetail pd = problems.validation(List.of(new FieldViolation(ex.field(), ex.getMessage())),
                request.getRequestURI());
        return ResponseEntity.status(pd.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
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

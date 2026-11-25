package com.telecom.support.servicecase;

import com.telecom.support.common.idempotency.IdempotencyService;
import com.telecom.support.common.security.ActorProvider;
import com.telecom.support.servicecase.CustomerCaseService.CaseDraft;
import com.telecom.support.servicecase.CustomerCaseService.CasePage;
import com.telecom.support.servicecase.CustomerCaseService.CaseSort;
import com.telecom.support.servicecase.CustomerCaseService.PreferredContact;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/customers/me/cases} — E11 list, E12 create (design 02-customer backend §4, §5, R1, R9, R10; openapi.yaml;
 * CUS-05, CUS-06). Both need their §8 permission and a customer principal with a linked customer, so staff and
 * unlinked customers get 403. Account, channel and contact number come from the principal; unknown JSON fields
 * ({@code accountId}, {@code channel}, …) are ignored.
 */
@RestController
@RequestMapping("/api/v1/customers/me/cases")
public class CustomerCaseController {

    /** Idempotency operation of E12 (design §5). */
    static final String CASE_CREATE_OPERATION = "CASE_CREATE";

    /** The {@code CaseCreateRequest} schema (no attachments: CUS-06.3 deferred, OQ 4). */
    public record CaseCreateRequest(
            @NotBlank @Size(max = 32) String categoryCode,
            @Size(min = 1, max = 32) String subcategoryCode,
            Long subscriptionId,
            @NotBlank @Size(min = 5, max = 150) String subject,
            @NotBlank @Size(min = 10, max = 4000) String description,
            @NotNull PreferredContact preferredContact) {}

    private final CustomerCaseService service;
    private final IdempotencyService idempotency;
    private final ActorProvider actors;

    CustomerCaseController(CustomerCaseService service, IdempotencyService idempotency, ActorProvider actors) {
        this.service = service;
        this.idempotency = idempotency;
        this.actors = actors;
    }

    @GetMapping
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CASE_READ)"
            + " and @customerScope.isCustomer()")
    public CasePage listMyCases(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Size(max = 24)
            @Pattern(regexp = "^SR-\\d{8}-\\d{6,}$", message = "must be a service request reference") String reference,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        return service.listMyCases(from, to, reference, page, size, CaseSort.fromParam(sort));
    }

    /**
     * E12. The canonical idempotent request is the body alone: this path has no path variables (D6), and the key scope
     * (user, {@code CASE_CREATE}, key) already separates it from other operations.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CASE_CREATE)"
            + " and @customerScope.isCustomer()")
    public ResponseEntity<?> createMyCase(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CaseCreateRequest body) {
        long userId = actors.currentActor().orElseThrow().id();
        return idempotency.execute(userId, CASE_CREATE_OPERATION, idempotencyKey, body,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(service.createMyCase(new CaseDraft(
                        body.categoryCode(), body.subcategoryCode(), body.subscriptionId(), body.subject(),
                        body.description(), body.preferredContact()))));
    }
}

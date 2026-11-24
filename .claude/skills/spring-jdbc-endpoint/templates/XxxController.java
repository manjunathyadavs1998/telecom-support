package com.telecom.support.xxx;

import com.telecom.support.common.web.PageResponse;
import com.telecom.support.xxx.dto.CreateXxxRequest;
import com.telecom.support.xxx.dto.XxxResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me/xxx")
@Tag(name = "Xxx")
public class XxxController {

    private final XxxService service;

    public XxxController(XxxService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List the caller's xxx (default last 3 months)")
    @PreAuthorize("hasAuthority(T(com.telecom.support.security.Permissions).XXX_READ)")
    public PageResponse<XxxResponse> list(@Valid XxxSearchParams params) {
        return service.list(params);
    }

    @PostMapping
    @Operation(summary = "Create xxx (idempotent with Idempotency-Key)")
    @PreAuthorize("hasAuthority(T(com.telecom.support.security.Permissions).XXX_CREATE)")
    public ResponseEntity<XxxResponse> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                              @Valid @RequestBody CreateXxxRequest request) {
        XxxResponse created = service.create(idempotencyKey, request);
        return ResponseEntity.created(URI.create("/api/v1/me/xxx/" + created.id())).body(created);
    }
}

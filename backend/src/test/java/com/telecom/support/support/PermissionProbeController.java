package com.telecom.support.support;

import com.telecom.support.identity.Permissions;
import java.util.Map;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only probes, one per {@link Permissions} constant, each guarded by {@code @PreAuthorize("hasAuthority(...)")}
 * (01 backend §3, §8; BR-10). {@link TestComponent} keeps it out of component scanning; ITs pull it in with
 * {@code @Import}, so it can never ship. {@code PermissionMatrixIT} drives every constant through {@link #PATH} and
 * checks that every probe method is guarded, so a new permission without a probe fails the build.
 */
@TestComponent
@RestController
@RequestMapping(PermissionProbeController.PATH)
public class PermissionProbeController {

    public static final String PATH = "/api/v1/test-probe/permission";

    @GetMapping("/" + Permissions.CUSTOMER_PROFILE_READ)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CUSTOMER_PROFILE_READ)")
    Map<String, String> customerProfileRead() {
        return granted(Permissions.CUSTOMER_PROFILE_READ);
    }

    @GetMapping("/" + Permissions.CUSTOMER_PROFILE_UPDATE)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CUSTOMER_PROFILE_UPDATE)")
    Map<String, String> customerProfileUpdate() {
        return granted(Permissions.CUSTOMER_PROFILE_UPDATE);
    }

    @GetMapping("/" + Permissions.CUSTOMER_LOOKUP)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CUSTOMER_LOOKUP)")
    Map<String, String> customerLookup() {
        return granted(Permissions.CUSTOMER_LOOKUP);
    }

    @GetMapping("/" + Permissions.CUSTOMER_VERIFY)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CUSTOMER_VERIFY)")
    Map<String, String> customerVerify() {
        return granted(Permissions.CUSTOMER_VERIFY);
    }

    @GetMapping("/" + Permissions.PLAN_READ)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).PLAN_READ)")
    Map<String, String> planRead() {
        return granted(Permissions.PLAN_READ);
    }

    @GetMapping("/" + Permissions.PLAN_CHANGE)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).PLAN_CHANGE)")
    Map<String, String> planChange() {
        return granted(Permissions.PLAN_CHANGE);
    }

    @GetMapping("/" + Permissions.PROMOTION_READ)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).PROMOTION_READ)")
    Map<String, String> promotionRead() {
        return granted(Permissions.PROMOTION_READ);
    }

    @GetMapping("/" + Permissions.PROMOTION_CLAIM)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).PROMOTION_CLAIM)")
    Map<String, String> promotionClaim() {
        return granted(Permissions.PROMOTION_CLAIM);
    }

    @GetMapping("/" + Permissions.CASE_CREATE)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CASE_CREATE)")
    Map<String, String> caseCreate() {
        return granted(Permissions.CASE_CREATE);
    }

    @GetMapping("/" + Permissions.CASE_CREATE_ON_BEHALF)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CASE_CREATE_ON_BEHALF)")
    Map<String, String> caseCreateOnBehalf() {
        return granted(Permissions.CASE_CREATE_ON_BEHALF);
    }

    @GetMapping("/" + Permissions.CASE_CATEGORY_INTERNAL)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CASE_CATEGORY_INTERNAL)")
    Map<String, String> caseCategoryInternal() {
        return granted(Permissions.CASE_CATEGORY_INTERNAL);
    }

    @GetMapping("/" + Permissions.CASE_READ)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CASE_READ)")
    Map<String, String> caseRead() {
        return granted(Permissions.CASE_READ);
    }

    @GetMapping("/" + Permissions.CASE_READ_QUEUE)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CASE_READ_QUEUE)")
    Map<String, String> caseReadQueue() {
        return granted(Permissions.CASE_READ_QUEUE);
    }

    @GetMapping("/" + Permissions.CASE_ASSIGN)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CASE_ASSIGN)")
    Map<String, String> caseAssign() {
        return granted(Permissions.CASE_ASSIGN);
    }

    @GetMapping("/" + Permissions.CASE_ASSIGN_ANY)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CASE_ASSIGN_ANY)")
    Map<String, String> caseAssignAny() {
        return granted(Permissions.CASE_ASSIGN_ANY);
    }

    @GetMapping("/" + Permissions.CASE_STATUS_UPDATE)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CASE_STATUS_UPDATE)")
    Map<String, String> caseStatusUpdate() {
        return granted(Permissions.CASE_STATUS_UPDATE);
    }

    @GetMapping("/" + Permissions.CASE_NOTE_INTERNAL)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CASE_NOTE_INTERNAL)")
    Map<String, String> caseNoteInternal() {
        return granted(Permissions.CASE_NOTE_INTERNAL);
    }

    @GetMapping("/" + Permissions.CASE_BOOKMARK)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CASE_BOOKMARK)")
    Map<String, String> caseBookmark() {
        return granted(Permissions.CASE_BOOKMARK);
    }

    @GetMapping("/" + Permissions.SERVICE_REACTIVATE)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).SERVICE_REACTIVATE)")
    Map<String, String> serviceReactivate() {
        return granted(Permissions.SERVICE_REACTIVATE);
    }

    @GetMapping("/" + Permissions.PAYMENT_READ)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).PAYMENT_READ)")
    Map<String, String> paymentRead() {
        return granted(Permissions.PAYMENT_READ);
    }

    @GetMapping("/" + Permissions.PAYMENT_INITIATE)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).PAYMENT_INITIATE)")
    Map<String, String> paymentInitiate() {
        return granted(Permissions.PAYMENT_INITIATE);
    }

    @GetMapping("/" + Permissions.ANALYTICS_READ)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).ANALYTICS_READ)")
    Map<String, String> analyticsRead() {
        return granted(Permissions.ANALYTICS_READ);
    }

    @GetMapping("/" + Permissions.AUDIT_READ)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).AUDIT_READ)")
    Map<String, String> auditRead() {
        return granted(Permissions.AUDIT_READ);
    }

    @GetMapping("/" + Permissions.REPORTS_EXPORT)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).REPORTS_EXPORT)")
    Map<String, String> reportsExport() {
        return granted(Permissions.REPORTS_EXPORT);
    }

    @GetMapping("/" + Permissions.REPORTS_EXPORT_PII)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).REPORTS_EXPORT_PII)")
    Map<String, String> reportsExportPii() {
        return granted(Permissions.REPORTS_EXPORT_PII);
    }

    private static Map<String, String> granted(String permission) {
        return Map.of("permission", permission);
    }
}

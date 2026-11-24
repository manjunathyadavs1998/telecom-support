package com.telecom.support.identity;

/**
 * Permission codes (BRD §8, design 01-foundation backend §3). They are the granted authorities of a {@link CurrentUser}.
 * Must equal the V2 {@code permissions} rows and the openapi {@code Permission} enum; {@code PermissionMatrixIT} checks
 * both. A change is a new Flyway migration plus an openapi change. Use as
 * {@code @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CASE_READ)")}.
 */
public final class Permissions {

    private Permissions() {}

    public static final String CUSTOMER_PROFILE_READ = "customer.profile.read";
    public static final String CUSTOMER_PROFILE_UPDATE = "customer.profile.update";
    public static final String CUSTOMER_LOOKUP = "customer.lookup";
    public static final String CUSTOMER_VERIFY = "customer.verify";
    public static final String PLAN_READ = "plan.read";
    public static final String PLAN_CHANGE = "plan.change";
    public static final String PROMOTION_READ = "promotion.read";
    public static final String PROMOTION_CLAIM = "promotion.claim";
    public static final String CASE_CREATE = "case.create";
    public static final String CASE_CREATE_ON_BEHALF = "case.create.onbehalf";
    public static final String CASE_CATEGORY_INTERNAL = "case.category.internal";
    public static final String CASE_READ = "case.read";
    public static final String CASE_READ_QUEUE = "case.read.queue";
    public static final String CASE_ASSIGN = "case.assign";
    public static final String CASE_ASSIGN_ANY = "case.assign.any";
    public static final String CASE_STATUS_UPDATE = "case.status.update";
    public static final String CASE_NOTE_INTERNAL = "case.note.internal";
    public static final String CASE_BOOKMARK = "case.bookmark";
    public static final String SERVICE_REACTIVATE = "service.reactivate";
    public static final String PAYMENT_READ = "payment.read";
    public static final String PAYMENT_INITIATE = "payment.initiate";
    public static final String ANALYTICS_READ = "analytics.read";
    public static final String AUDIT_READ = "audit.read";
    public static final String REPORTS_EXPORT = "reports.export";
    public static final String REPORTS_EXPORT_PII = "reports.export.pii";
}

package com.telecom.support.security;

/**
 * Permission identifiers (BRD §8). Keep in sync with V2 migration, frontend/src/permissions/permissions.ts and
 * PermissionMatrixIT (rbac-permission skill). Use in {@code @PreAuthorize("hasAuthority(T(...Permissions).X)")}.
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

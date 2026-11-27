package com.telecom.support.servicecase;

import com.telecom.support.audit.AuditService;
import com.telecom.support.common.error.ApiExceptions.BusinessRuleException;
import com.telecom.support.common.reference.ReferenceGenerator;
import com.telecom.support.common.security.ActorProvider.Actor;
import com.telecom.support.servicecase.CaseRepository.NewCase;
import com.telecom.support.servicecase.CategoryRepository.CategoryRow;
import com.telecom.support.servicecase.SlaPolicyRepository.SlaPolicyRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The shared core of filing a case (design 02-customer backend R10 / 03-inbound backend R12; BR-01/02/04/07): resolve
 * the leaf of a category tree and its derived severity, find the SLA row of a severity (leaf, then parent; none → 422),
 * and write the case with its PUBLIC CREATED activity, an optional INTERNAL note and {@code CASE_CREATED}. Callers own
 * the account, contact, permission and state checks and the transaction.
 */
@Service
class CaseCreationService {

    static final String CASE_CREATED = "CASE_CREATED";
    static final String ENTITY_CASE = "CASE";
    static final String STATUS_CREATED = "CREATED";

    /**
     * The selected leaf ({@code parent} null when the top level is the leaf), its top level (whose audience counts) and
     * the derived severity: the leaf default, else the parent's; null when neither has one.
     */
    record Classification(CategoryRow top, CategoryRow leaf, CategoryRow parent, String derivedSeverity) {}

    /** A case to write; {@code subscriptionId} and {@code internalNote} are optional. */
    record CaseSpec(long accountId, Long subscriptionId, Classification classification, String severity,
                    SlaPolicyRow sla, String channel, String subject, String description, String preferredContact,
                    String contactMsisdn, Actor creator, String internalNote, Map<String, Object> auditDetails) {}

    /** What was written. */
    record CreatedCase(long id, String reference, String caseType, String severity, Instant createdAt) {}

    private final CaseRepository cases;
    private final CaseActivityRepository activity;
    private final SlaPolicyRepository slaPolicies;
    private final ReferenceGenerator references;
    private final AuditService audit;
    private final Clock clock;

    CaseCreationService(CaseRepository cases, CaseActivityRepository activity, SlaPolicyRepository slaPolicies,
                        ReferenceGenerator references, AuditService audit, Clock clock) {
        this.cases = cases;
        this.activity = activity;
        this.slaPolicies = slaPolicies;
        this.references = references;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * 02 R10: {@code categoryCode} must be a top level of {@code tree} (the selectable categories, top levels and their
     * children); {@code subcategoryCode} is required exactly when it has children in the tree and must be one of them;
     * otherwise 422.
     */
    Classification classify(List<CategoryRow> tree, String categoryCode, String subcategoryCode) {
        CategoryRow top = tree.stream()
                .filter(c -> c.parentId() == null && c.code().equals(categoryCode))
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException("Category " + categoryCode + " is not selectable",
                        "Choose one of the listed categories."));
        List<CategoryRow> children = tree.stream().filter(c -> Objects.equals(c.parentId(), top.id())).toList();

        CategoryRow leaf;
        CategoryRow parent;
        if (children.isEmpty()) {
            if (subcategoryCode != null) {
                throw new BusinessRuleException("Category " + categoryCode + " has no subcategory " + subcategoryCode,
                        "This category has no subcategories.");
            }
            leaf = top;
            parent = null;
        } else {
            leaf = children.stream()
                    .filter(c -> c.code().equals(subcategoryCode))
                    .findFirst()
                    .orElseThrow(() -> new BusinessRuleException(
                            "Subcategory " + subcategoryCode + " is not selectable under " + categoryCode,
                            "Choose one of the listed subcategories."));
            parent = top;
        }
        String severity = leaf.defaultSeverity() != null ? leaf.defaultSeverity()
                : parent != null ? parent.defaultSeverity() : null;
        return new Classification(top, leaf, parent, severity);
    }

    /** BR-07: the active SLA row of (leaf, severity), else (parent, severity); none (or no severity) → 422. */
    SlaPolicyRow slaFor(Classification classification, String severity) {
        CategoryRow leaf = classification.leaf();
        CategoryRow parent = classification.parent();
        SlaPolicyRow sla = severity == null ? null : slaPolicies.findActive(leaf.id(), severity)
                .or(() -> parent == null ? Optional.empty() : slaPolicies.findActive(parent.id(), severity))
                .orElse(null);
        if (sla == null) {
            throw new BusinessRuleException("No SLA policy for category " + leaf.code() + " severity " + severity,
                    "Requests of this type cannot be raised right now. Please choose another category or try later.");
        }
        return sla;
    }

    /**
     * Writes the case (status CREATED, due times from the SLA row), its PUBLIC CREATED activity, the INTERNAL note when
     * given, and {@code CASE_CREATED} with {@code category}, {@code caseType}, {@code severity}, {@code channel} and then
     * the caller's extra details. Runs in the caller's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    CreatedCase create(CaseSpec spec) {
        Instant now = clock.instant();
        CategoryRow leaf = spec.classification().leaf();
        String reference = references.next(ReferenceGenerator.SERVICE_REQUEST);
        long caseId = cases.insert(new NewCase(reference, spec.accountId(), spec.subscriptionId(), leaf.id(),
                leaf.caseType(), spec.channel(), spec.subject(), spec.description(), spec.severity(), STATUS_CREATED,
                spec.preferredContact(), spec.contactMsisdn(), spec.creator().id(),
                now.plus(Duration.ofMinutes(spec.sla().responseMinutes())),
                now.plus(Duration.ofMinutes(spec.sla().resolutionMinutes())), now));
        activity.insertCreated(caseId, spec.creator().id(), now);
        if (spec.internalNote() != null) {
            activity.insertInternalNote(caseId, spec.creator().id(), spec.internalNote(), now);
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("category", leaf.code());
        details.put("caseType", leaf.caseType());
        details.put("severity", spec.severity());
        details.put("channel", spec.channel());
        details.putAll(spec.auditDetails());
        audit.record(CASE_CREATED, spec.creator().id(), spec.creator().username(), ENTITY_CASE, reference, details);
        return new CreatedCase(caseId, reference, leaf.caseType(), spec.severity(), now);
    }
}

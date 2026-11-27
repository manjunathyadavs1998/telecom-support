package com.telecom.support.servicecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.support.common.error.ApiExceptions.BusinessRuleException;
import com.telecom.support.common.security.ActorProvider.Actor;
import com.telecom.support.servicecase.CaseCreationService.CaseSpec;
import com.telecom.support.servicecase.CaseCreationService.Classification;
import com.telecom.support.servicecase.CaseCreationService.CreatedCase;
import com.telecom.support.servicecase.CategoryRepository.CategoryRow;
import com.telecom.support.servicecase.SlaPolicyRepository.SlaPolicyRow;
import com.telecom.support.support.AbstractMySqlIT;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The shared case-filing core extracted from 02 E12 (design 03-inbound backend R12, §6; 02 R10; BR-01/02/04/07):
 * classification on a given tree, the SLA lookup (leaf, then parent; none → 422) and the write of case, activity and
 * audit inside the caller's transaction. {@code CustomerCaseIT} proves the 02 behaviour through E12 unchanged. Cases are
 * written on CUST-T-0001's account; they cannot be removed (append-only activity) and the 02 ITs only assert test-data
 * references, so they do not interfere.
 */
class CaseCreationServiceIT extends AbstractMySqlIT {

    @Autowired
    CaseCreationService creation;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PlatformTransactionManager transactions;

    @AfterEach
    void removeTemporaryCategories() {
        jdbc.update("DELETE FROM categories WHERE code = 'T_CCS_NOSLA'", Map.of());
    }

    // ------------------------------------------------------------------ classify (pure, over the given tree)

    private static final CategoryRow TOP = new CategoryRow(1, "TOP", "Top", null, "REQUEST", "MEDIUM");
    private static final CategoryRow CHILD_OWN = new CategoryRow(2, "CHILD_OWN", "Own", 1L, "COMPLAINT", "HIGH");
    private static final CategoryRow CHILD_INHERIT = new CategoryRow(3, "CHILD_INH", "Inherit", 1L, "REQUEST", null);
    private static final CategoryRow LONE = new CategoryRow(4, "LONE", "Lone", null, "COMPLAINT", null);
    private static final List<CategoryRow> TREE = List.of(TOP, LONE, CHILD_OWN, CHILD_INHERIT);

    @Test
    void classifyResolvesTheLeafAndItsDerivedSeverity() {
        Classification own = creation.classify(TREE, "TOP", "CHILD_OWN");
        assertThat(own.top()).isEqualTo(TOP);
        assertThat(own.leaf()).isEqualTo(CHILD_OWN);
        assertThat(own.parent()).isEqualTo(TOP);
        assertThat(own.derivedSeverity()).isEqualTo("HIGH");

        assertThat(creation.classify(TREE, "TOP", "CHILD_INH").derivedSeverity()).isEqualTo("MEDIUM");

        Classification lone = creation.classify(TREE, "LONE", null);
        assertThat(lone.leaf()).isEqualTo(LONE);
        assertThat(lone.parent()).isNull();
        assertThat(lone.derivedSeverity()).isNull();
    }

    @Test
    void classifyRejectsWhatTheTreeDoesNotOffer() {
        assertThatThrownBy(() -> creation.classify(TREE, "NOPE", null)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> creation.classify(TREE, "CHILD_OWN", null)) // a child is not a top level
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> creation.classify(TREE, "TOP", null)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> creation.classify(TREE, "TOP", "LONE")).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> creation.classify(TREE, "LONE", "CHILD_OWN"))
                .isInstanceOf(BusinessRuleException.class);
    }

    // ------------------------------------------------------------------ slaFor (BR-07)

    @Test
    void slaFallsBackFromTheLeafToItsParentAndIsRequired() {
        CategoryRow parent = category("INT_BACKOFFICE");
        CategoryRow leaf = category("INT_BILL_ADJUST"); // no own SLA rows: the parent's apply (V8)
        Classification classification = new Classification(parent, leaf, parent, "MEDIUM");

        for (String severity : List.of("LOW", "MEDIUM", "HIGH", "CRITICAL")) {
            SlaPolicyRow sla = creation.slaFor(classification, severity);
            assertThat(sla.categoryId()).isEqualTo(parent.id());
            assertThat(sla.severity()).isEqualTo(severity);
        }
        assertThatThrownBy(() -> creation.slaFor(classification, null)).isInstanceOf(BusinessRuleException.class);

        jdbc.update("""
                INSERT INTO categories (code, name, parent_id, audience, case_type, default_severity, active,
                                        sort_order, created_at, updated_at)
                VALUES ('T_CCS_NOSLA', 'No SLA', NULL, 'CUSTOMER', 'REQUEST', 'LOW', TRUE, 999,
                        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, Map.of());
        CategoryRow bare = category("T_CCS_NOSLA");
        assertThatThrownBy(() -> creation.slaFor(new Classification(bare, bare, null, "LOW"), "LOW"))
                .isInstanceOf(BusinessRuleException.class);
    }

    // ------------------------------------------------------------------ create

    @Test
    void createNeedsTheCallersTransaction() {
        CaseSpec spec = spec("not-written", null);
        assertThatThrownBy(() -> creation.create(spec)).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cases WHERE subject = 'not-written'", Map.of(),
                Long.class)).isZero();
    }

    @Test
    void createWritesTheCaseItsActivityAndTheAuditWithExtraDetails() throws Exception {
        CreatedCase created = new TransactionTemplate(transactions).execute(tx -> creation.create(
                spec("Shared core probe", "Agent's internal note")));

        assertThat(created.reference()).matches("SR-\\d{8}-\\d{6,}");
        assertThat(created.caseType()).isEqualTo("REQUEST");
        assertThat(created.severity()).isEqualTo("HIGH");
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT c.channel, c.status, c.severity, c.case_type, c.preferred_contact, c.contact_msisdn,
                       c.subject, c.description, c.subscription_id, cat.code AS category, a.account_ref,
                       u.username AS creator, c.created_at, c.response_due_at, c.sla_due_at
                FROM cases c
                JOIN categories cat ON cat.id = c.category_id
                JOIN accounts a ON a.id = c.account_id
                JOIN users u ON u.id = c.created_by_user_id
                WHERE c.reference = :ref
                """, Map.of("ref", created.reference()));
        assertThat(row).containsEntry("channel", "INBOUND_AGENT").containsEntry("status", "CREATED")
                .containsEntry("severity", "HIGH").containsEntry("case_type", "REQUEST")
                .containsEntry("preferred_contact", "SMS").containsEntry("contact_msisdn", "+919900000001")
                .containsEntry("category", "INT_BILL_ADJUST").containsEntry("account_ref", "ACC-T-0001")
                .containsEntry("creator", "T-IN").containsEntry("subscription_id", null);
        LocalDateTime createdAt = (LocalDateTime) row.get("created_at");
        assertThat(createdAt.toInstant(ZoneOffset.UTC)).isEqualTo(created.createdAt().truncatedTo(ChronoUnit.MICROS));
        assertThat(Duration.between(createdAt, (LocalDateTime) row.get("response_due_at"))).hasMinutes(60);
        assertThat(Duration.between(createdAt, (LocalDateTime) row.get("sla_due_at"))).hasMinutes(1440);

        List<Map<String, Object>> activity = jdbc.queryForList("""
                SELECT a.activity_type, a.visibility, a.new_value, a.note_text
                FROM case_activity a JOIN cases c ON c.id = a.case_id
                WHERE c.reference = :ref ORDER BY a.created_at, a.id
                """, Map.of("ref", created.reference()));
        assertThat(activity).hasSize(2);
        assertThat(activity.get(0)).containsEntry("activity_type", "CREATED").containsEntry("visibility", "PUBLIC")
                .containsEntry("new_value", "CREATED").containsEntry("note_text", null);
        assertThat(activity.get(1)).containsEntry("activity_type", "NOTE").containsEntry("visibility", "INTERNAL")
                .containsEntry("note_text", "Agent's internal note");

        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT actor_username, entity_type, outcome, details FROM audit_events
                WHERE action = 'CASE_CREATED' AND entity_id = :ref
                """, Map.of("ref", created.reference()));
        assertThat(audit).containsEntry("actor_username", "T-IN").containsEntry("entity_type", "CASE")
                .containsEntry("outcome", "SUCCESS");
        Map<String, Object> details = objectMapper.readValue((String) audit.get("details"), new TypeReference<>() {});
        assertThat(details).containsEntry("category", "INT_BILL_ADJUST").containsEntry("caseType", "REQUEST")
                .containsEntry("severity", "HIGH").containsEntry("channel", "INBOUND_AGENT")
                .containsEntry("severityDerived", "MEDIUM");
    }

    @Test
    void withoutANoteOnlyTheCreatedActivityIsWritten() {
        CreatedCase created = new TransactionTemplate(transactions).execute(tx -> creation.create(
                spec("Shared core probe without note", null)));

        assertThat(jdbc.queryForList("""
                SELECT a.activity_type FROM case_activity a JOIN cases c ON c.id = a.case_id WHERE c.reference = :ref
                """, Map.of("ref", created.reference()), String.class)).containsExactly("CREATED");
    }

    // ------------------------------------------------------------------ helpers

    /** An INBOUND_AGENT case by T-IN on ACC-T-0001 under INT_BILL_ADJUST, severity HIGH (derived MEDIUM). */
    private CaseSpec spec(String subject, String note) {
        CategoryRow parent = category("INT_BACKOFFICE");
        CategoryRow leaf = category("INT_BILL_ADJUST");
        Classification classification = new Classification(parent, leaf, parent, "MEDIUM");
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("severityDerived", "MEDIUM");
        long accountId = jdbc.queryForObject("SELECT id FROM accounts WHERE account_ref = 'ACC-T-0001'", Map.of(),
                Long.class);
        long agentId = jdbc.queryForObject("SELECT id FROM users WHERE username = 'T-IN'", Map.of(), Long.class);
        return new CaseSpec(accountId, null, classification, "HIGH", creation.slaFor(classification, "HIGH"),
                "INBOUND_AGENT", subject, "Description of the shared core probe.", "SMS", "+919900000001",
                new Actor(agentId, "T-IN"), note, extra);
    }

    private CategoryRow category(String code) {
        return jdbc.queryForObject("""
                SELECT id, code, name, parent_id, case_type, default_severity FROM categories WHERE code = :code
                """, Map.of("code", code), (rs, i) -> new CategoryRow(rs.getLong("id"), rs.getString("code"),
                rs.getString("name"), rs.getObject("parent_id", Long.class), rs.getString("case_type"),
                rs.getString("default_severity")));
    }
}

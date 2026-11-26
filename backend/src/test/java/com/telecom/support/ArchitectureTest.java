package com.telecom.support;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Module and persistence rules (ADR 0001, ADR 0002; design 01-foundation backend §8, 00-overview §1).
 */
class ArchitectureTest {

    private static final String ROOT = "com.telecom.support";

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    @Test
    void noJpaOrSpringData() {
        noClasses()
                .should().dependOnClassesThat().resideInAnyPackage("jakarta.persistence..", "org.springframework.data..")
                .because("ADR 0002: Spring JDBC only, no JPA or Spring Data")
                .check(productionClasses);
    }

    @Test
    void repositoriesAreOnlyReachableFromTheirOwnModule() {
        classes()
                .that().haveSimpleNameEndingWith("Repository")
                .should(onlyBeAccessedFromTheirOwnModule())
                .because("00-overview §1: a module calls another module's *Service, never its repository")
                .allowEmptyShould(true)
                .check(productionClasses);
    }

    @Test
    void topLevelPackagesAreFreeOfCycles() {
        slices().matching(ROOT + ".(*)..")
                .should().beFreeOfCycles()
                .because("ADR 0001: modules (common, audit, identity, ...) form an acyclic graph so a feature can be split out")
                .check(productionClasses);
    }

    @Test
    void catalogDependsOnNoCustomerFacingModule() {
        noClasses()
                .that().resideInAPackage(ROOT + ".catalog..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        ROOT + ".customer..", ROOT + ".servicecase..", ROOT + ".payment..")
                .because("02-customer backend §1, §9: catalog is reference data used by customer, not the reverse")
                .check(productionClasses);
    }

    @Test
    void customerDependsOnNeitherCasesNorPayments() {
        noClasses()
                .that().resideInAPackage(ROOT + ".customer..")
                .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".servicecase..", ROOT + ".payment..")
                .because("02-customer backend §1: servicecase and payment depend on customer, one way only")
                .check(productionClasses);
    }

    /**
     * 00-overview rev 4 §1 / 03-inbound backend §1: the only allowed edges between feature modules. Every module may also
     * use {@code common.*} and {@code audit} (except {@code notification}, see below); anything else is a violation.
     */
    private static final Map<String, Set<String>> ALLOWED_FEATURE_EDGES = Map.of(
            "identity", Set.of(),
            "catalog", Set.of(),
            "customer", Set.of("catalog"),
            "servicecase", Set.of("customer", "identity"),
            "payment", Set.of("customer"),
            "reactivation", Set.of("customer", "notification", "identity"),
            "notification", Set.of());

    @Test
    void featureModulesUseOnlyTheListedEdges() {
        classes()
                .that().resideInAnyPackage(ALLOWED_FEATURE_EDGES.keySet().stream()
                        .map(module -> ROOT + "." + module + "..").toArray(String[]::new))
                .should(dependOnlyOnAllowedModules())
                .because("00-overview rev 4 §1: one-way edges only (identity imports no feature module)")
                .allowEmptyShould(true)
                .check(productionClasses);
    }

    @Test
    void notificationUsesOnlyCommon() {
        noClasses()
                .that().resideInAPackage(ROOT + ".notification..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".audit..")
                .because("00-overview rev 4 §1: notification depends only on common")
                .allowEmptyShould(true)
                .check(productionClasses);
    }

    private static ArchCondition<JavaClass> dependOnlyOnAllowedModules() {
        return new ArchCondition<>("depend only on common, audit, itself and its listed modules") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String module = moduleOf(origin);
                Set<String> allowed = ALLOWED_FEATURE_EDGES.get(module);
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    if (!target.getPackageName().startsWith(ROOT + ".")) {
                        continue;
                    }
                    String targetModule = moduleOf(target);
                    if (targetModule.equals(module) || targetModule.equals("audit")
                            || targetModule.startsWith("common") || allowed.contains(targetModule)) {
                        continue;
                    }
                    events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                }
            }
        };
    }

    private static ArchCondition<JavaClass> onlyBeAccessedFromTheirOwnModule() {
        return new ArchCondition<>("only be accessed from their own module") {
            @Override
            public void check(JavaClass repository, ConditionEvents events) {
                String module = moduleOf(repository);
                for (Dependency dependency : repository.getDirectDependenciesToSelf()) {
                    if (!module.equals(moduleOf(dependency.getOriginClass()))) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    /**
     * Module of a class per 00-overview §1: the first package below {@code com.telecom.support}, except under
     * {@code common}, where each sub-package (e.g. {@code common.idempotency}) is its own module.
     */
    static String moduleOf(JavaClass javaClass) {
        String pkg = javaClass.getPackageName();
        if (!pkg.startsWith(ROOT + ".")) {
            return pkg;
        }
        String[] parts = pkg.substring(ROOT.length() + 1).split("\\.");
        if (parts[0].equals("common") && parts.length > 1) {
            return parts[0] + "." + parts[1];
        }
        return parts[0];
    }
}

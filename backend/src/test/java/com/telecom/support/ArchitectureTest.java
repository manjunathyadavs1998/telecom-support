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

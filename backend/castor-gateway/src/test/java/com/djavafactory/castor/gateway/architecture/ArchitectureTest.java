package com.djavafactory.castor.gateway.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/** The same layering as castor-core, plus rule R8: server code never reads the wall clock or sleeps. */
class ArchitectureTest {

    private static final String ROOT = "com.djavafactory.castor.gateway";

    private final JavaClasses production = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    @Test
    void domainHasNoFrameworkImports() {
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("io.quarkus..", "org.hibernate..", "jakarta..")
                .because("domain code is pure Java so it can be tested alone and reused")
                .check(production);
    }

    @Test
    void domainDoesNotDependOnOtherLayers() {
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..api..", "..application..", "..infrastructure..")
                .because("dependencies point towards the domain")
                .check(production);
    }

    @Test
    void apiDoesNotUseInfrastructure() {
        noClasses()
                .that()
                .resideInAPackage("..api..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..infrastructure..")
                .because("the api layer talks to use cases, never to adapters")
                .check(production);
    }

    @Test
    void contextsHaveNoCycles() {
        slices().matching(ROOT + ".(*)..").should().beFreeOfCycles().check(production);
    }

    @Test
    void serverCodeDoesNotReadTheWallClockOrSleep() {
        noClasses()
                .should()
                .callMethod(java.time.Instant.class, "now")
                .orShould()
                .callMethod(java.time.Instant.class, "now", java.time.Clock.class)
                .orShould()
                .callMethod(java.time.OffsetDateTime.class, "now")
                .orShould()
                .callMethod(java.time.LocalDateTime.class, "now")
                .orShould()
                .callMethod(System.class, "currentTimeMillis")
                .orShould()
                .callMethod(Thread.class, "sleep", long.class)
                .because("time comes from the injected InstantSource (rule R8)")
                .check(production);
    }
}

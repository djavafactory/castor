package com.djavafactory.castor.core.architecture;

import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/** The architecture rules, kept apart from the classes they check so fixtures can prove each rule bites. */
final class ArchitectureRules {

    static final ArchRule DOMAIN_IS_FRAMEWORK_FREE = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("io.quarkus..", "org.hibernate..", "jakarta..")
            .because("domain code is pure Java so it can be tested alone and reused");

    static final ArchRule DOMAIN_DOES_NOT_POINT_OUTWARD = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..api..", "..application..", "..infrastructure..")
            .because("dependencies point towards the domain");

    static final ArchRule API_SKIPS_INFRASTRUCTURE = noClasses()
            .that()
            .resideInAPackage("..api..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..infrastructure..")
            .because("the api layer talks to use cases, never to adapters");

    static ArchRule contextsHaveNoCycles(String rootPackage) {
        return slices().matching(rootPackage + ".(*)..").should().beFreeOfCycles();
    }

    private ArchitectureRules() {}
}

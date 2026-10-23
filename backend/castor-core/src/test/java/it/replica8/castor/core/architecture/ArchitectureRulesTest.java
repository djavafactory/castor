package it.replica8.castor.core.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** Each rule must fail on a deliberate violation, otherwise a green ArchitectureTest proves nothing. */
class ArchitectureRulesTest {

    private static final String FIXTURES = "it.replica8.castor.archfixture";

    private final JavaClasses violation = new ClassFileImporter().importPackages(FIXTURES + ".violation");
    private final JavaClasses cycle = new ClassFileImporter().importPackages(FIXTURES + ".cycle");

    @Test
    void frameworkImportInDomainIsRejected() {
        assertThrows(AssertionError.class, () -> ArchitectureRules.DOMAIN_IS_FRAMEWORK_FREE.check(violation));
    }

    @Test
    void domainDependingOnInfrastructureIsRejected() {
        assertThrows(AssertionError.class, () -> ArchitectureRules.DOMAIN_DOES_NOT_POINT_OUTWARD.check(violation));
    }

    @Test
    void apiUsingInfrastructureIsRejected() {
        assertThrows(AssertionError.class, () -> ArchitectureRules.API_SKIPS_INFRASTRUCTURE.check(violation));
    }

    @Test
    void cycleBetweenContextsIsRejected() {
        assertThrows(
                AssertionError.class,
                () -> ArchitectureRules.contextsHaveNoCycles(FIXTURES + ".cycle")
                        .check(cycle));
    }
}

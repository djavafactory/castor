package it.replica8.castor.core.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    private static final String ROOT = "it.replica8.castor.core";

    private final JavaClasses production = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    @Test
    void domainHasNoFrameworkImports() {
        ArchitectureRules.DOMAIN_IS_FRAMEWORK_FREE.check(production);
    }

    @Test
    void domainDoesNotDependOnOtherLayers() {
        ArchitectureRules.DOMAIN_DOES_NOT_POINT_OUTWARD.check(production);
    }

    @Test
    void apiDoesNotUseInfrastructure() {
        ArchitectureRules.API_SKIPS_INFRASTRUCTURE.check(production);
    }

    @Test
    void contextsHaveNoCycles() {
        ArchitectureRules.contextsHaveNoCycles(ROOT).check(production);
    }
}

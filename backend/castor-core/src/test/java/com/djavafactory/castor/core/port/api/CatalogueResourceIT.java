package com.djavafactory.castor.core.port.api;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestSecurity(user = "viewer", roles = "VIEWER")
class CatalogueResourceIT {

    @Test
    void returnsEveryCatalogueSection() {
        given().when()
                .get("/api/v1/catalogue")
                .then()
                .statusCode(200)
                .header("Cache-Control", equalTo("private, max-age=300"))
                .body("cargoTypes", hasSize(13))
                .body("shipClasses", hasSize(10))
                .body("cargoFamilies", hasSize(5))
                .body("surfaces", hasSize(4))
                .body(
                        "accuracyClasses.code",
                        equalTo(java.util.List.of("SURVEYED", "VERIFIED", "OPERATOR", "APPROXIMATE")))
                .body("assetCategories", hasSize(11))
                .body("paletteGroups.code", hasItems("CRANES", "HANDLING", "ZONES"))
                .body("densityNote", equalTo("Densities are typical values, not a standard."));
    }

    @Test
    void cargoTypesCarryTheCatalogueFields() {
        given().when()
                .get("/api/v1/catalogue")
                .then()
                .body("cargoTypes.find { it.code == 'COAL' }.name", equalTo("Thermal coal"))
                .body("cargoTypes.find { it.code == 'COAL' }.familyCode", equalTo("DRY_BULK"))
                .body("cargoTypes.find { it.code == 'COAL' }.baseUnit", equalTo("t"))
                .body("cargoTypes.find { it.code == 'COAL' }.densityTM3", equalTo(0.85f))
                .body("cargoTypes.find { it.code == 'COAL' }.storageForm", equalTo("PILE"))
                .body("cargoTypes.find { it.code == 'COAL' }.reposeDeg", equalTo(37.0f))
                .body("cargoTypes.find { it.code == 'COAL' }.colourHex", equalTo("#2B2B30"))
                .body("cargoTypes.find { it.code == 'STEEL' }.maxStackM", equalTo(5.0f))
                .body("cargoTypes.find { it.code == 'GRAIN' }.reposeDeg", nullValue());
    }

    @Test
    void shipClassesCarryTheirDimensions() {
        given().when()
                .get("/api/v1/catalogue")
                .then()
                .body("shipClasses.find { it.code == 'SUPRAMAX' }.loaM", equalTo(199.0f))
                .body("shipClasses.find { it.code == 'SUPRAMAX' }.beamM", equalTo(32.2f))
                .body("shipClasses.find { it.code == 'SUPRAMAX' }.draftM", equalTo(12.8f))
                .body("shipClasses.find { it.code == 'SUPRAMAX' }.dwtT", equalTo(58000))
                .body("shipClasses.find { it.code == 'CONT_L' }.kind", equalTo("CONTAINER"));
    }

    @Test
    void matchingEtagGives304() {
        String etag = given().when()
                .get("/api/v1/catalogue")
                .then()
                .statusCode(200)
                .extract()
                .header("ETag");
        given().header("If-None-Match", etag)
                .when()
                .get("/api/v1/catalogue")
                .then()
                .statusCode(304);
    }
}

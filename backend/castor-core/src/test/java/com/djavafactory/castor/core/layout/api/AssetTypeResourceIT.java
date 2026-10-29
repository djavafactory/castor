package com.djavafactory.castor.core.layout.api;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
@TestSecurity(user = "viewer", roles = "VIEWER")
class AssetTypeResourceIT {

    @Test
    void servesTheWholeRegistryGroupedByPalette() {
        given().when()
                .get("/api/v1/asset-types")
                .then()
                .statusCode(200)
                .header("Cache-Control", equalTo("private, max-age=300"))
                .body("$", hasSize(58))
                .body("[0].paletteGroup", equalTo("CRANES"))
                .body("[0].code", equalTo("MOBILE_HARBOUR_CRANE"))
                .body("[-1].paletteGroup", equalTo("ZONES"));
    }

    @Test
    void berthRowCarriesGeometryFlagsAndLimits() {
        given().when()
                .get("/api/v1/asset-types")
                .then()
                .body("find { it.code == 'BERTH' }.geometryKind", equalTo("QUAY_SEGMENT"))
                .body("find { it.code == 'BERTH' }.placementMode", equalTo("PLACE"))
                .body("find { it.code == 'BERTH' }.flags.mayExtendOverWater", equalTo(true))
                .body("find { it.code == 'BERTH' }.flags.mustBeOnBerth", equalTo(false))
                .body("find { it.code == 'BERTH' }.defaults.lengthM", equalTo(200.0f))
                .body("find { it.code == 'BERTH' }.min.lengthM", equalTo(20.0f))
                .body("find { it.code == 'BERTH' }.max.lengthM", nullValue())
                .body("find { it.code == 'BERTH' }.params.depthM", equalTo(14))
                .body("find { it.code == 'BERTH' }.modelKey", equalTo("berth"))
                .body("find { it.code == 'BERTH' }.modelRev", equalTo(1))
                .body("find { it.code == 'BERTH' }.colourHex", equalTo("#E8A317"))
                .body("find { it.code == 'BERTH' }.purpose", startsWith("Stretch of quay where a vessel moors"));
    }

    @Test
    void newMaterialHandlingTypesAreServed() {
        given().when()
                .get("/api/v1/asset-types")
                .then()
                .body("find { it.code == 'EXCAVATOR' }.paletteGroup", equalTo("HANDLING"))
                .body("find { it.code == 'EXCAVATOR' }.defaults.lengthM", equalTo(10.0f))
                .body("find { it.code == 'EXCAVATOR' }.flags.mobile", equalTo(true))
                .body("find { it.code == 'MOBILE_CONVEYOR_LOADER' }.category", equalTo("DRY_BULK"))
                .body("find { it.code == 'MOBILE_CONVEYOR_LOADER' }.params.rateTph", equalTo(800))
                .body("find { it.code == 'MOBILE_CONVEYOR_LOADER' }.colourHex", equalTo("#4F6D8F"));
    }

    @Test
    void hintsAndCapacitiesAreServed() {
        given().when()
                .get("/api/v1/asset-types")
                .then()
                .body(
                        "find { it.code == 'FENDER' }.hint",
                        equalTo(
                                "Click along the quay edge, then double-click or press Enter. Fenders are spaced every 6 m."))
                .body("find { it.code == 'TRANSIT_SHED' }.defaultCapacityValue", equalTo(900.0f))
                .body("find { it.code == 'TRANSIT_SHED' }.defaultCapacityUnit", equalTo("t"));
    }

    @Test
    void matchingEtagGives304() {
        String etag = given().when()
                .get("/api/v1/asset-types")
                .then()
                .statusCode(200)
                .extract()
                .header("ETag");
        given().header("If-None-Match", etag)
                .when()
                .get("/api/v1/asset-types")
                .then()
                .statusCode(304);
    }
}

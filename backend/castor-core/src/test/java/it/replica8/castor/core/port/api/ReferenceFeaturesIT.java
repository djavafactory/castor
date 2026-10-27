package it.replica8.castor.core.port.api;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Westports port record and its read-only OpenStreetMap reference features, seeded into a real PostGIS. */
@QuarkusTest
@TestProfile(ReferenceFeaturesIT.ResetOnStart.class)
@TestSecurity(user = "viewer", roles = "VIEWER")
class ReferenceFeaturesIT {

    public static class ResetOnStart implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("castor.env", "test", "castor.data.reset-on-start", "true");
        }
    }

    @Test
    void portCarriesTheQuayFrame() {
        var port = given().when()
                .get("/api/v1/ports/westports")
                .then()
                .statusCode(200)
                .extract()
                .as(PortResource.PortResponse.class);
        assertEquals("westports", port.code());
        assertEquals(3380, port.measureSrid());
        assertEquals(101.3066304, port.origin().lon(), 1e-9);
        assertEquals(2.9534057, port.origin().lat(), 1e-9);
        assertEquals(-120.299, port.rotDeg(), 1e-9);
    }

    @Test
    void configuredPortIsServedForTheHeader() {
        given().when().get("/api/v1/port").then().statusCode(200).body("name", equalTo("Westports Malaysia"));
    }

    @Test
    void unknownPortIsNotFound() {
        given().when().get("/api/v1/ports/nowhere").then().statusCode(404);
        given().when().get("/api/v1/ports/nowhere/reference-features").then().statusCode(404);
    }

    @Test
    void buildingsComeBackAsGeoJsonWithMeasuredAreaAndHeight() {
        given().when()
                .get("/api/v1/ports/westports/reference-features?kind=BUILDING")
                .then()
                .statusCode(200)
                .body("type", equalTo("FeatureCollection"))
                .body("features", hasSize(127))
                .body("features.type", everyItem(equalTo("Feature")))
                .body("features.geometry.type", everyItem(equalTo("Polygon")))
                .body("features.properties.kind", everyItem(equalTo("BUILDING")))
                .body("features.properties.heightM", everyItem(notNullValue()))
                .body("features[0].properties.areaM2", greaterThan(0f))
                .body("features[0].properties.osmRef", notNullValue());
    }

    @Test
    void buildingsAreTheDefaultKind() {
        given().when()
                .get("/api/v1/ports/westports/reference-features")
                .then()
                .statusCode(200)
                .body("features", hasSize(127));
    }

    @Test
    void unknownKindIsRejected() {
        given().when()
                .get("/api/v1/ports/westports/reference-features?kind=TREE")
                .then()
                .statusCode(422);
    }

    @Test
    void referenceFeaturesCannotBeChanged() {
        given().contentType("application/json")
                .body("{}")
                .when()
                .post("/api/v1/ports/westports/reference-features")
                .then()
                .statusCode(405);
        given().when()
                .delete("/api/v1/ports/westports/reference-features")
                .then()
                .statusCode(405);
    }
}

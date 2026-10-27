package com.djavafactory.castor.core.port.infrastructure;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import javax.sql.DataSource;

import jakarta.inject.Inject;

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
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The committed Westports reference file is imported at start-up: 127 buildings and 504 road lines. */
@QuarkusTest
@TestProfile(ReferenceSeedIT.ImportOn.class)
@TestSecurity(user = "viewer", roles = "VIEWER")
class ReferenceSeedIT {

    public static class ImportOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("castor.reference.import-on-start", "true");
        }
    }

    @Inject
    DataSource dataSource;

    private long count(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection();
                ResultSet rs = c.createStatement().executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void importsTheCommittedFileOnceWithItsChecksum() throws SQLException {
        assertEquals(127, count("select count(*) from ref.reference_feature where kind = 'BUILDING'"));
        assertEquals(504, count("select count(*) from ref.reference_feature where kind = 'ROAD'"));
        assertEquals(
                1,
                count("select count(*) from ref.import_batch where status = 'SUCCEEDED' and row_count = 631 "
                        + "and length(checksum) = 64"));
    }

    @Test
    void buildingsComeBackAsGeoJsonWithMeasuredAreaAndHeight() {
        given().queryParam("kind", "BUILDING")
                .when()
                .get("/api/v1/ports/westports/reference-features")
                .then()
                .statusCode(200)
                .body("type", equalTo("FeatureCollection"))
                .body("features", hasSize(127))
                .body("features.type", everyItem(equalTo("Feature")))
                .body("features.geometry.type", everyItem(equalTo("Polygon")))
                .body("features.properties.kind", everyItem(equalTo("BUILDING")))
                .body("features.properties.height_m", everyItem(notNullValue()))
                .body("features.properties.area_m2", everyItem(greaterThan(0f)))
                .body("features.properties.source", everyItem(equalTo("OSM")))
                .body("features.properties.accuracy", everyItem(equalTo("APPROXIMATE")))
                .body("features[0].properties.osm_ref", notNullValue());
    }

    @Test
    void roadsAreLinesWithAWidthAndNoArea() {
        given().queryParam("kind", "ROAD")
                .when()
                .get("/api/v1/ports/westports/reference-features")
                .then()
                .statusCode(200)
                .body("features", hasSize(504))
                .body("features.geometry.type", everyItem(equalTo("LineString")))
                .body("features.properties.width_m", everyItem(notNullValue()))
                .body("features.properties.area_m2", everyItem(nullValue()));
    }

    @Test
    void generatedBuildingNamesAreNotShownAsNames() {
        given().queryParam("kind", "BUILDING")
                .when()
                .get("/api/v1/ports/westports/reference-features")
                .then()
                .body("features.properties.name", everyItem(nullValue()));
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

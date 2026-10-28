package com.djavafactory.castor.core.layout.api;

import java.util.UUID;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.layout.infrastructure.LayoutSeedJob;
import com.djavafactory.castor.core.support.LayoutTestSupport;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

/** DESIGNER-11 part 1: GET /api/v1/layouts/{layoutId}/accuracy-report and the Westports seed's accuracy. */
@QuarkusTest
class AccuracyReportIT {

    @Inject
    DataSource dataSource;

    @Inject
    LayoutSeedJob seed;

    @BeforeEach
    void clean() {
        LayoutTestSupport.clear(dataSource);
    }

    @AfterEach
    void clear() {
        LayoutTestSupport.clear(dataSource);
    }

    private String publishedWestports() {
        seed.seed();
        return given().get("/api/v1/ports/westports/layouts/current")
                .then()
                .statusCode(200)
                .extract()
                .path("layout.id");
    }

    @Test
    @TestSecurity(user = "alice", roles = "EDITOR")
    void westportsSeedMeetsTheAccuracyAcceptanceCriteria() {
        String published = publishedWestports();

        given().get("/api/v1/layouts/" + published + "/accuracy-report")
                .then()
                .statusCode(200)
                .body("controlPoints", hasSize(4))
                .body("maxResidualM", lessThanOrEqualTo(0.1f))
                .body("maxRoundTripMm", lessThan(1f))
                .body("countsByAccuracy.APPROXIMATE", equalTo(301))
                .body("countsByAccuracy.SURVEYED", equalTo(0))
                .body("countsByAccuracy.VERIFIED", equalTo(0))
                .body("countsByAccuracy.OPERATOR", equalTo(0))
                .body("approximateCount", equalTo(301))
                .body("note", containsString("satellite tracing"));
    }

    @Test
    @TestSecurity(user = "alice", roles = "EDITOR")
    void everyControlPointCarriesItsCodeNameAndRoundTrip() {
        String published = publishedWestports();

        given().get("/api/v1/layouts/" + published + "/accuracy-report")
                .then()
                .statusCode(200)
                .body(
                        "controlPoints.find { it.code == 'CP-MAIN-GATE' }.name",
                        equalTo("Westport Conventional Main Gate (map listing)"))
                .body("controlPoints.every { it.residualM != null }", equalTo(true))
                .body("controlPoints.every { it.roundTripMm >= 0 }", equalTo(true));
    }

    @Test
    @TestSecurity(user = "viola", roles = "VIEWER")
    void viewersMayReadTheReport() {
        String published = publishedWestports();

        given().get("/api/v1/layouts/" + published + "/accuracy-report").then().statusCode(200);
    }

    @Test
    void anonymousCallersAreNotLetIn() {
        // refused before the version is looked up, so no layout is needed
        given().get("/api/v1/layouts/" + UUID.randomUUID() + "/accuracy-report")
                .then()
                .statusCode(401);
    }

    @Test
    @TestSecurity(user = "alice", roles = "EDITOR")
    void unknownLayoutIsNotFound() {
        given().get("/api/v1/layouts/" + UUID.randomUUID() + "/accuracy-report")
                .then()
                .statusCode(404)
                .body("status", equalTo(404));
    }
}

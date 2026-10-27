package com.djavafactory.castor.core.layout.api;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.layout.application.CreateDraft;
import com.djavafactory.castor.core.layout.application.LayoutValidation;
import com.djavafactory.castor.core.layout.infrastructure.LayoutSeedJob;
import com.djavafactory.castor.core.support.LayoutTestSupport;
import com.djavafactory.castor.core.support.ShapeFixtures.Asset;

import static com.djavafactory.castor.core.support.ShapeFixtures.rect;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGNER-03 parts 1 and 2: the validate endpoint, the Westports seed and the speed of both entry points. */
@QuarkusTest
class LayoutValidateIT {

    @Inject
    DataSource dataSource;

    @Inject
    LayoutSeedJob seed;

    @Inject
    CreateDraft createDraft;

    @Inject
    LayoutValidation validation;

    @BeforeEach
    void clean() {
        LayoutTestSupport.clear(dataSource);
    }

    @AfterEach
    void clear() {
        LayoutTestSupport.clear(dataSource);
    }

    private UUID draftWith(String... overlapping) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            UUID draft = LayoutTestSupport.version(c, 1, "DRAFT");
            for (int i = 0; i < overlapping.length; i += 2) {
                UUID id = LayoutTestSupport.asset(c, overlapping[i], "BUILDING");
                LayoutTestSupport.revision(c, id, draft, overlapping[i + 1], rectOf(i));
                sized(c, id, draft);
            }
            return draft;
        }
    }

    /** The size every hall needs: a building without length and width is itself an error. */
    private static void sized(Connection c, UUID asset, UUID draft) throws SQLException {
        try (var ps = c.prepareStatement(
                "update layout.asset_revision set length_m = 100, width_m = 40 where asset_id = ? and layout_version_id = ?")) {
            ps.setObject(1, asset);
            ps.setObject(2, draft);
            ps.executeUpdate();
        }
    }

    /** Two neighbouring halls that share 10 m x 40 m. */
    private static String rectOf(int i) {
        return i == 0
                ? LayoutTestSupport.localRect(1000, 100, 1100, 140)
                : LayoutTestSupport.localRect(1090, 100, 1190, 140);
    }

    private long auditRows() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return LayoutTestSupport.scalar(c, "select count(*) from audit.audit_log", Long.class);
        }
    }

    // ---- POST /api/v1/layouts/{layoutId}/validate --------------------------------------------------------------

    @Test
    @TestSecurity(user = "alice", roles = "EDITOR")
    void draftWithOneOverlapHasOneErrorWithBothAssetIds() throws SQLException {
        UUID draft = draftWith("WH-A", "Warehouse A", "WH-B", "Warehouse B");
        String a;
        String b;
        try (Connection c = dataSource.getConnection()) {
            a = LayoutTestSupport.scalar(c, "select id::text from layout.asset where code = 'WH-A'", String.class);
            b = LayoutTestSupport.scalar(c, "select id::text from layout.asset where code = 'WH-B'", String.class);
        }

        given().post("/api/v1/layouts/" + draft + "/validate")
                .then()
                .statusCode(200)
                .body("errors", equalTo(1))
                .body("issues", hasSize(2))
                .body("issues.find { it.code == 'OVERLAP' }.level", equalTo("ERROR"))
                .body("issues.find { it.code == 'OVERLAP' }.message", equalTo("Overlaps Warehouse B"))
                .body("issues.find { it.code == 'OVERLAP' }.assetIds", containsInAnyOrder(a, b))
                .body("warnings", equalTo(1))
                .body("issues.find { it.code == 'APPROXIMATE_SOURCE' }.level", equalTo("WARNING"));
    }

    @Test
    @TestSecurity(user = "alice", roles = "EDITOR")
    void cleanDraftHasOnlyTheNoSurveyWarning() throws SQLException {
        UUID draft = draftWith("WH-A", "Warehouse A");

        given().post("/api/v1/layouts/" + draft + "/validate")
                .then()
                .statusCode(200)
                .body("errors", equalTo(0))
                .body("warnings", equalTo(1))
                .body("issues[0].message", equalTo("1 shape has no survey"));
    }

    @Test
    @TestSecurity(user = "alice", roles = "EDITOR")
    void emptyDraftHasNoIssues() throws SQLException {
        UUID draft = draftWith();

        given().post("/api/v1/layouts/" + draft + "/validate")
                .then()
                .statusCode(200)
                .body("errors", equalTo(0))
                .body("warnings", equalTo(0))
                .body("issues", empty());
    }

    @Test
    @TestSecurity(user = "alice", roles = "EDITOR")
    void validatingWritesNothingAndNoAuditRow() throws SQLException {
        UUID draft = draftWith("WH-A", "Warehouse A", "WH-B", "Warehouse B");
        long before = auditRows();

        given().post("/api/v1/layouts/" + draft + "/validate").then().statusCode(200);
        given().post("/api/v1/layouts/" + draft + "/validate").then().statusCode(200);

        assertEquals(before, auditRows());
    }

    @Test
    @TestSecurity(user = "viola", roles = "VIEWER")
    void viewersMayNotValidate() throws SQLException {
        UUID draft = draftWith();
        given().post("/api/v1/layouts/" + draft + "/validate").then().statusCode(403);
    }

    @Test
    void anonymousCallersAreNotLetIn() throws SQLException {
        UUID draft = draftWith();
        given().post("/api/v1/layouts/" + draft + "/validate").then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "alice", roles = "EDITOR")
    void unknownLayoutIsNotFound() {
        given().post("/api/v1/layouts/" + UUID.randomUUID() + "/validate")
                .then()
                .statusCode(404)
                .body("status", equalTo(404));
    }

    // ---- The Westports seed ------------------------------------------------------------------------------------

    @Test
    @TestSecurity(user = "alice", roles = "EDITOR")
    void westportsSeedValidatesWithZeroErrors() {
        seed.seed();
        String published = given().get("/api/v1/ports/westports/layouts/current")
                .then()
                .statusCode(200)
                .extract()
                .path("layout.id");

        var errors = given().post("/api/v1/layouts/" + published + "/validate")
                .then()
                .statusCode(200)
                .body("errors", equalTo(0))
                .extract()
                .path("issues.findAll { it.level == 'ERROR' }.message");

        assertTrue(((List<?>) errors).isEmpty(), "seed errors: " + errors);
    }

    // ---- Speed -------------------------------------------------------------------------------------------------

    @Test
    void layoutOfFiveHundredAssetsIsValidatedInUnderTwoSeconds() throws SQLException {
        seed.seed();
        var draft = createDraft.execute("westports", "published", "alice");
        addHalls(draft.id(), 500 - 301);

        validation.layout(draft.id()); // warm up: connection pool, JIT, query plans
        long start = System.nanoTime();
        var report = validation.layout(draft.id());
        long ms = (System.nanoTime() - start) / 1_000_000;

        System.out.println("validateLayout of 500 assets: " + ms + " ms, "
                + report.issues().size() + " issues");
        assertTrue(ms < 2000, "validateLayout took " + ms + " ms");
    }

    @Test
    void singleAssetCheckIsUnderFiftyMillisecondsAtTheNinetyFifthPercentile() throws SQLException {
        seed.seed();
        var draft = createDraft.execute("westports", "published", "alice");
        addHalls(draft.id(), 500 - 301);
        var candidate = Asset.of(null, "WAREHOUSE", "Warehouse A", rect(1000, 700, 1080, 740))
                .size(80.0, 40.0)
                .build();

        for (int i = 0; i < 20; i++) {
            validation.asset(draft.id(), candidate); // warm up
        }
        var millis = new ArrayList<Double>();
        for (int i = 0; i < 100; i++) {
            long start = System.nanoTime();
            validation.asset(draft.id(), candidate);
            millis.add((System.nanoTime() - start) / 1_000_000.0);
        }
        Collections.sort(millis);
        double p95 = millis.get((int) Math.ceil(millis.size() * 0.95) - 1);

        System.out.println("validateAsset p95 with 500 assets: " + p95 + " ms");
        assertTrue(p95 < 50, "p95 was " + p95 + " ms");
    }

    /** Adds small halls on a grid in the yard behind the quay, so the draft holds the given number more. */
    private void addHalls(UUID draft, int count) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            for (int i = 0; i < count; i++) {
                double x = -3000 + (i % 40) * 60;
                double y = 1000 + (i / 40) * 40;
                UUID id = LayoutTestSupport.asset(c, "HALL-" + i, "BUILDING");
                LayoutTestSupport.revision(
                        c, id, draft, "Hall " + i, LayoutTestSupport.localRect(x, y, x + 40, y + 30));
            }
        }
    }
}

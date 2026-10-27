package com.djavafactory.castor.core.search.api;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.RestAssured;
import io.restassured.config.JsonConfig;
import io.restassured.http.ContentType;
import io.restassured.path.json.config.JsonPathConfig;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.layout.application.CreateDraft;
import com.djavafactory.castor.core.layout.infrastructure.LayoutSeedJob;
import com.djavafactory.castor.core.port.domain.DataSourceCode;
import com.djavafactory.castor.core.port.infrastructure.OsmImportJob;
import com.djavafactory.castor.core.support.LayoutTestSupport;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** DESIGNER-02 part 3: place search over the port's own data, against the seeded Westports layout. */
@QuarkusTest
@TestSecurity(user = "viewer", roles = "VIEWER")
class SearchResourceIT {

    private static final String SEARCH = "/api/v1/ports/westports/search";

    /** Coordinates are compared to 1e-7 degrees, so numbers are read as doubles, not floats. */
    @BeforeAll
    static void readDoubles() {
        RestAssured.config = RestAssured.config()
                .jsonConfig(JsonConfig.jsonConfig().numberReturnType(JsonPathConfig.NumberReturnType.DOUBLE));
    }

    @AfterAll
    static void restoreConfig() {
        RestAssured.reset();
    }

    @Inject
    LayoutSeedJob seed;

    @Inject
    OsmImportJob importJob;

    @Inject
    CreateDraft createDraft;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void seedWestports() throws SQLException {
        clean();
        seed.seed();
        importJob.run("westports", DataSourceCode.OSM, "fixtures/osm-sample.geojson", false);
    }

    @AfterEach
    void clean() throws SQLException {
        LayoutTestSupport.clear(dataSource);
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().executeUpdate("delete from ref.reference_feature");
            c.createStatement().executeUpdate("delete from ref.import_batch");
        }
    }

    private ValidatableResponse search(String q) {
        return given().queryParam("q", q).get(SEARCH).then();
    }

    @Test
    void berthTwoFindsBerthTwoFirst() {
        search("berth 2")
                .statusCode(200)
                .contentType(ContentType.JSON)
                .header("Cache-Control", equalTo("no-store"))
                .body("[0].kind", equalTo("ASSET"))
                .body("[0].label", equalTo("Berth 2"))
                .body("[0].sublabel", equalTo("Berth"))
                .body("[0].id", notNullValue())
                .body("[0].lon", closeTo(101.306585, 1e-6))
                .body("[0].lat", closeTo(2.9533276, 1e-6))
                .body("[0].bbox", hasSize(4));
    }

    @Test
    void gateFindsTheMainGateControlPointAndTheGateAssets() {
        search("gate")
                .statusCode(200)
                .body("findAll { it.kind == 'CONTROL_POINT' }.id", contains("CP-MAIN-GATE"))
                .body("findAll { it.kind == 'CONTROL_POINT' }[0].sublabel", equalTo("Control point"))
                .body("findAll { it.kind == 'ASSET' }.label", hasItem("Gate house"))
                .body("findAll { it.kind == 'ASSET' }.label", hasItem("Main gate"));
    }

    @Test
    void theMainGateControlPointIsAtItsListedPosition() {
        search("cp-main-gate")
                .statusCode(200)
                .body("[0].kind", equalTo("CONTROL_POINT"))
                .body("[0].lon", closeTo(101.3093602, 1e-7))
                .body("[0].lat", closeTo(2.9518844, 1e-7));
    }

    @Test
    void thePortEntryIsTheLayoutOrigin() {
        search("westports")
                .statusCode(200)
                .body("[0].kind", equalTo("PORT"))
                .body("[0].id", equalTo("westports"))
                .body("[0].label", equalTo("Westports Malaysia, Pulau Indah (layout origin)"))
                .body("[0].lon", closeTo(101.3066304, 1e-7))
                .body("[0].lat", closeTo(2.9534057, 1e-7));
    }

    @Test
    void matchesCodesAndIgnoresCase() {
        search("B2").statusCode(200).body("label", hasItem("Berth 2"));
        search("BERTH 2").statusCode(200).body("[0].label", equalTo("Berth 2"));
    }

    @Test
    void findsOpenStreetMapShapesByName() {
        search("shed a")
                .statusCode(200)
                .body("[0].kind", equalTo("REFERENCE"))
                .body("[0].label", equalTo("Shed A"))
                .body("[0].sublabel", equalTo("Building (OpenStreetMap)"));
    }

    @Test
    void returnsAtMostTwelvePlaces() {
        search("er").statusCode(200).body("size()", equalTo(12));
    }

    @Test
    void nothingFoundIsAnEmptyList() {
        search("zzzz-nothing").statusCode(200).body("$", empty());
    }

    @Test
    void wildcardsAreOrdinaryCharacters() {
        search("%%").statusCode(200).body("$", empty());
        search("__").statusCode(200).body("$", empty());
    }

    @Test
    void whatIsTypedIsNeitherLoggedNorStored() throws SQLException {
        var lines = new CopyOnWriteArrayList<String>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                lines.add(new java.util.logging.SimpleFormatter().formatMessage(record));
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        var root = java.util.logging.Logger.getLogger("");
        long auditBefore = rows("select count(*) from audit.audit_log");
        root.addHandler(capture);
        try {
            search("zq-typed-secret").statusCode(200);
        } finally {
            root.removeHandler(capture);
        }

        assertFalse(lines.stream().anyMatch(l -> l.contains("zq-typed-secret")), "the text is in a log line");
        assertEquals(auditBefore, rows("select count(*) from audit.audit_log"), "a search wrote an audit row");
    }

    private long rows(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return LayoutTestSupport.scalar(c, sql, Long.class);
        }
    }

    @Test
    void theTextMustBeAtLeastTwoCharacters() {
        search("g").statusCode(400).contentType("application/problem+json");
        search("  ").statusCode(400);
        given().get(SEARCH).then().statusCode(400);
    }

    @Test
    void unknownPortIsNotFound() {
        given().queryParam("q", "gate")
                .get("/api/v1/ports/nowhere/search")
                .then()
                .statusCode(404);
    }

    @Test
    @TestSecurity(user = "alice", roles = "EDITOR")
    void anEditorSearchesTheAssetsOfTheDraft() {
        UUID draft = createDraft.execute("westports", "published", "alice").id();
        given().queryParam("q", "berth 2")
                .queryParam("layoutId", draft)
                .get(SEARCH)
                .then()
                .statusCode(200)
                .body("[0].label", equalTo("Berth 2"));
    }

    @Test
    void aViewerCannotSearchADraft() {
        UUID draft = createDraft.execute("westports", "published", "alice").id();
        given().queryParam("q", "berth 2")
                .queryParam("layoutId", draft)
                .get(SEARCH)
                .then()
                .statusCode(404);
    }

    @Test
    void aVersionThatDoesNotExistIsNotFound() {
        given().queryParam("q", "berth 2")
                .queryParam("layoutId", UUID.randomUUID())
                .get(SEARCH)
                .then()
                .statusCode(404);
    }

    @Test
    void assetsOfThePublishedVersionAreSearchedWhenNoLayoutIsGiven() {
        List<String> kinds = search("berth 2").extract().path("kind");
        assertEquals("ASSET", kinds.get(0));
        search("berth 2").body("size()", lessThanOrEqualTo(12));
    }
}

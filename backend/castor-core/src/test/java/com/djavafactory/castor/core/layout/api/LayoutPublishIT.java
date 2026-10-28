package com.djavafactory.castor.core.layout.api;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.layout.infrastructure.LayoutSeedJob;
import com.djavafactory.castor.core.support.LayoutTestSupport;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGNER-09 parts 1 to 3: publish, restore, the version list, the asset history and the diff, on the Westports seed. */
@QuarkusTest
class LayoutPublishIT {

    private static final String PORT = "/api/v1/ports/westports/layouts";
    private static final String HASH = "$2a$12$" + "a".repeat(53);

    @Inject
    DataSource dataSource;

    @Inject
    LayoutSeedJob seed;

    @BeforeEach
    void seedWestports() throws SQLException {
        LayoutTestSupport.clear(dataSource);
        seed.seed();
        run("delete from security.user_account where username in ('rita', 'vera')");
        user("rita", "PUBLISHER");
        user("vera", "VIEWER");
    }

    @AfterEach
    void clear() throws SQLException {
        LayoutTestSupport.clear(dataSource);
        run("delete from security.user_account where username in ('rita', 'vera')");
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private void run(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection();
                var s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    private <T> T scalar(String sql, Class<T> type) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return LayoutTestSupport.scalar(c, sql, type);
        }
    }

    private void user(String name, String role) throws SQLException {
        run("insert into security.user_account (username, display_name, password_hash) values ('" + name + "', '" + name
                + "', '" + HASH + "')");
        run("insert into security.user_role select id, '" + role + "' from security.user_account where username = '"
                + name + "'");
    }

    private String draftFromPublished() {
        return given().contentType(ContentType.JSON)
                .body(Map.of("basedOn", "published"))
                .post(PORT)
                .then()
                .statusCode(201)
                .extract()
                .path("layout.id");
    }

    private String publishedId() {
        return given().get(PORT + "/current").then().statusCode(200).extract().path("layout.id");
    }

    private Set<String> assetIds(String layoutId) {
        List<String> ids = given().get("/api/v1/layouts/" + layoutId)
                .then()
                .statusCode(200)
                .extract()
                .path("features.features.id");
        return new HashSet<>(ids);
    }

    private long audit(String action) throws SQLException {
        return scalar("select count(*) from audit.audit_log where action = '" + action + "'", Long.class);
    }

    /** The first two terminal tractors of a version by code: one to move, one to delete (nothing else depends on them). */
    private List<String> twoTractors(String layoutId) throws SQLException {
        try (Connection c = dataSource.getConnection();
                var ps = c.prepareStatement("select a.id::text from layout.asset a join layout.asset_revision r "
                        + "on r.asset_id = a.id where r.layout_version_id = ?::uuid and a.type_code = 'TERMINAL_TRACTOR' "
                        + "order by a.code limit 2")) {
            ps.setString(1, layoutId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                String first = rs.getString(1);
                rs.next();
                return List.of(first, rs.getString(1));
            }
        }
    }

    private void move(String layoutId, String assetId) throws SQLException {
        run("update layout.asset_revision set geom = ST_Translate(geom, 0.0000005, 0), "
                + "anchor = ST_Translate(anchor, 0.0000005, 0) where layout_version_id = '" + layoutId
                + "' and asset_id = '" + assetId + "'");
    }

    private void remove(String layoutId, String assetId) throws SQLException {
        run("delete from layout.asset_revision where layout_version_id = '" + layoutId + "' and asset_id = '" + assetId
                + "'");
    }

    private void publish(String layoutId) {
        given().contentType(ContentType.JSON)
                .body(Map.of("note", "x"))
                .post("/api/v1/layouts/" + layoutId + "/publish")
                .then()
                .statusCode(200);
    }

    // ---- Part 1: publish ----------------------------------------------------------------------------------------

    @Test
    @TestSecurity(user = "pat", roles = "PUBLISHER")
    void publishWithAnErrorIsRefusedAndChangesNothing() throws SQLException {
        String draft = draftFromPublished();
        String v1 = publishedId();
        // a second building on exactly the shape of the first overlaps it
        run("insert into layout.asset (id, port_id, code, type_code, created_by) select gen_random_uuid(), port_id, "
                + "'DUP-1', 'BUILDING', 'test' from layout.asset limit 1");
        run("insert into layout.asset_revision (asset_id, layout_version_id, name, geom, anchor, length_m, width_m, "
                + "height_m, accuracy_code, source_code, created_by, updated_by) select (select id from layout.asset "
                + "where code = 'DUP-1'), layout_version_id, 'Duplicate', geom, anchor, length_m, width_m, height_m, "
                + "accuracy_code, source_code, 'test', 'test' from layout.asset_revision r join layout.asset a on "
                + "a.id = r.asset_id where r.layout_version_id = '" + draft + "' and a.type_code = 'BUILDING' "
                + "order by a.code limit 1");

        given().contentType(ContentType.JSON)
                .body(Map.of("note", "try"))
                .post("/api/v1/layouts/" + draft + "/publish")
                .then()
                .statusCode(422)
                .body("title", equalTo("Publishing blocked: 1 error(s). Fix them first."))
                .body("errors", hasSize(1))
                .body("errors[0].code", equalTo("OVERLAP"));

        assertEquals(
                "DRAFT", scalar("select status from layout.layout_version where id = '" + draft + "'", String.class));
        assertEquals(v1, publishedId());
        assertEquals(0, audit("LAYOUT_PUBLISHED"));
    }

    @Test
    @TestSecurity(user = "pat", roles = "PUBLISHER")
    void publishMakesTheDraftTheCurrentVersionWithAuthorTimeNoteAndReviewer() throws SQLException {
        String v1 = publishedId();
        var before = given().get(PORT + "/current")
                .then()
                .extract()
                .path("features.features.geometry")
                .toString();
        String draft = draftFromPublished();

        given().contentType(ContentType.JSON)
                .body(Map.of("note", "Moved gate", "reviewedBy", "rita"))
                .post("/api/v1/layouts/" + draft + "/publish")
                .then()
                .statusCode(200)
                .body("versionNo", equalTo(2))
                .body("publishedBy", equalTo("pat"))
                .body("reviewedBy", equalTo("rita"))
                .body("assetCount", equalTo(301))
                .body("publishedAt", org.hamcrest.Matchers.notNullValue());

        given().get(PORT + "/current")
                .then()
                .statusCode(200)
                .body("layout.id", equalTo(draft))
                .body("layout.versionNo", equalTo(2))
                .body("layout.status", equalTo("PUBLISHED"))
                .body("layout.note", equalTo("Moved gate"))
                .body("layout.publishedBy", equalTo("pat"));
        assertEquals(
                before,
                given().get(PORT + "/current")
                        .then()
                        .extract()
                        .path("features.features.geometry")
                        .toString());
        assertEquals(
                "SUPERSEDED", scalar("select status from layout.layout_version where id = '" + v1 + "'", String.class));
        assertEquals(
                draft,
                scalar(
                        "select current_published_layout_id::text from ref.port where code = 'westports'",
                        String.class));
        given().get(PORT + "/draft").then().statusCode(404);
        assertEquals(1, audit("LAYOUT_PUBLISHED"));
    }

    @Test
    @TestSecurity(user = "pat", roles = "PUBLISHER")
    void aPublishedVersionCannotBePublishedAgainOrHaveAnOverlongNote() {
        given().contentType(ContentType.JSON)
                .body(Map.of("note", "again"))
                .post("/api/v1/layouts/" + publishedId() + "/publish")
                .then()
                .statusCode(409);
        String draft = draftFromPublished();
        given().contentType(ContentType.JSON)
                .body(Map.of("note", "n".repeat(501)))
                .post("/api/v1/layouts/" + draft + "/publish")
                .then()
                .statusCode(400);
        given().contentType(ContentType.JSON)
                .body(Map.of("note", "n".repeat(500)))
                .post("/api/v1/layouts/" + draft + "/publish")
                .then()
                .statusCode(200);
    }

    @Test
    @TestSecurity(user = "pat", roles = "PUBLISHER")
    void reviewerMustBeAnotherPublisherOrAdmin() throws SQLException {
        String draft = draftFromPublished();
        for (String reviewer : List.of("pat", "vera", "nobody")) {
            given().contentType(ContentType.JSON)
                    .body(Map.of("reviewedBy", reviewer))
                    .post("/api/v1/layouts/" + draft + "/publish")
                    .then()
                    .statusCode(422)
                    .body("title", equalTo("The reviewer is not accepted"))
                    .body("errors[0].code", equalTo("REVIEWER_INVALID"));
        }
        assertEquals(
                "DRAFT", scalar("select status from layout.layout_version where id = '" + draft + "'", String.class));
        // nobody named is fine
        given().contentType(ContentType.JSON)
                .body(Map.of("note", ""))
                .post("/api/v1/layouts/" + draft + "/publish")
                .then()
                .statusCode(200)
                .body("reviewedBy", nullValue());
    }

    @Test
    @TestSecurity(user = "ed", roles = "EDITOR")
    void editorCannotPublishOrRestore() {
        String draft = draftFromPublished();
        given().contentType(ContentType.JSON)
                .body(Map.of())
                .post("/api/v1/layouts/" + draft + "/publish")
                .then()
                .statusCode(403);
        given().contentType(ContentType.JSON)
                .body(Map.of())
                .post("/api/v1/layouts/" + publishedId() + "/restore")
                .then()
                .statusCode(403);
    }

    @Test
    void publishedRowsCannotBeModifiedOrDeleted() throws SQLException {
        String v1 = scalar("select id::text from layout.layout_version where status = 'PUBLISHED'", String.class);
        for (String sql : List.of(
                "update layout.layout_version set note = 'changed' where id = '" + v1 + "'",
                "update layout.layout_version set published_by = 'someone' where id = '" + v1 + "'",
                "update layout.layout_version set status = 'DRAFT' where id = '" + v1 + "'",
                "delete from layout.layout_version where id = '" + v1 + "'",
                "update layout.asset_revision set name = 'changed' where layout_version_id = '" + v1 + "'")) {
            var e = assertThrows(SQLException.class, () -> run(sql), sql);
            assertTrue(e.getMessage().contains("cannot be") || e.getMessage().contains("not a draft"), e.getMessage());
        }
    }

    // ---- Part 2: restore ----------------------------------------------------------------------------------------

    @Test
    @TestSecurity(user = "pat", roles = "PUBLISHER")
    void restoreOfV1AfterV2GivesADraftWithV1AssetIdsAndPublishingItCreatesV3() throws SQLException {
        String v1 = publishedId();
        Set<String> v1Ids = assetIds(v1);
        String draft2 = draftFromPublished();
        remove(draft2, twoTractors(draft2).get(1));
        publish(draft2);
        assertEquals(300, assetIds(publishedId()).size());

        var restored = given().contentType(ContentType.JSON)
                .body(Map.of())
                .post("/api/v1/layouts/" + v1 + "/restore")
                .then()
                .statusCode(201)
                .body("layout.status", equalTo("DRAFT"))
                .body("layout.versionNo", equalTo(3))
                .body(
                        "message",
                        equalTo("Revision 1 loaded. Asset IDs are unchanged. Save the draft or publish to keep it."))
                .extract();
        String draft3 = restored.path("layout.id");
        assertEquals(v1Ids, assetIds(draft3));
        assertEquals(1, audit("LAYOUT_RESTORED"));

        given().contentType(ContentType.JSON)
                .body(Map.of("note", "Back to v1"))
                .post("/api/v1/layouts/" + draft3 + "/publish")
                .then()
                .statusCode(200)
                .body("versionNo", equalTo(3))
                .body("assetCount", equalTo(301));

        given().get(PORT + "?status=PUBLISHED")
                .then()
                .statusCode(200)
                .body("[0].versionNo", equalTo(3))
                .body("[0].restoredFromVersionNo", equalTo(1))
                .body("[0].note", equalTo("Back to v1"));
        assertEquals(v1Ids, assetIds(publishedId()));
    }

    @Test
    @TestSecurity(user = "pat", roles = "PUBLISHER")
    void restoreWithAnOpenDraftIsAConflictUnlessTheDraftIsDiscarded() throws SQLException {
        String v1 = publishedId();
        String draft2 = draftFromPublished();
        publish(draft2);
        String openDraft = draftFromPublished();

        given().contentType(ContentType.JSON)
                .body(Map.of())
                .post("/api/v1/layouts/" + v1 + "/restore")
                .then()
                .statusCode(409)
                .body("draftId", equalTo(openDraft));

        given().contentType(ContentType.JSON)
                .body(Map.of("discardDraft", true))
                .post("/api/v1/layouts/" + v1 + "/restore")
                .then()
                .statusCode(201)
                .body("layout.status", equalTo("DRAFT"));
        assertEquals(
                "DISCARDED",
                scalar("select status from layout.layout_version where id = '" + openDraft + "'", String.class));
        assertEquals(1, audit("DRAFT_DISCARDED"));
    }

    @Test
    @TestSecurity(user = "pat", roles = "PUBLISHER")
    void onlyAPublishedOrSupersededVersionCanBeRestored() {
        String draft = draftFromPublished();
        given().contentType(ContentType.JSON)
                .body(Map.of("discardDraft", true))
                .post("/api/v1/layouts/" + draft + "/restore")
                .then()
                .statusCode(400);
        given().contentType(ContentType.JSON)
                .body(Map.of())
                .post("/api/v1/layouts/" + UUID.randomUUID() + "/restore")
                .then()
                .statusCode(404);
    }

    // ---- Part 3: list, history, diff ----------------------------------------------------------------------------

    @Test
    @TestSecurity(user = "pat", roles = "PUBLISHER")
    void versionListIsNewestFirstWithAuthorsReviewerAndAssetCount() {
        String draft = draftFromPublished();
        given().contentType(ContentType.JSON)
                .body(Map.of("note", "Second", "reviewedBy", "rita"))
                .post("/api/v1/layouts/" + draft + "/publish")
                .then()
                .statusCode(200);
        draftFromPublished();

        given().get(PORT)
                .then()
                .statusCode(200)
                .body("versionNo", org.hamcrest.Matchers.contains(3, 2, 1))
                .body("status", org.hamcrest.Matchers.contains("DRAFT", "PUBLISHED", "SUPERSEDED"))
                .body("[1].publishedBy", equalTo("pat"))
                .body("[1].reviewedBy", equalTo("rita"))
                .body("[1].note", equalTo("Second"))
                .body("[1].assetCount", equalTo(301))
                .body("[1].restoredFromVersionNo", nullValue());
        given().get(PORT + "?status=SUPERSEDED").then().body("versionNo", org.hamcrest.Matchers.contains(1));
        given().get(PORT + "?limit=1").then().body("$", hasSize(1)).body("[0].versionNo", equalTo(3));
        given().get(PORT + "?limit=0").then().statusCode(400);
        given().get(PORT + "?status=BOGUS").then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "vic", roles = "VIEWER")
    void viewerSeesOnlyPublishedAndSupersededVersions() throws SQLException {
        run("update layout.layout_version set status = 'SUPERSEDED' where status = 'PUBLISHED'");
        UUID draft = UUID.randomUUID();
        run("insert into layout.layout_version (id, port_id, version_no, status, created_by) select '" + draft
                + "', port_id, 2, 'DRAFT', 'x' from layout.layout_version limit 1");

        given().get(PORT).then().statusCode(200).body("versionNo", org.hamcrest.Matchers.contains(1));
        given().get(PORT + "?status=DRAFT").then().statusCode(200).body("$", empty());
        given().get("/api/v1/layouts/" + draft + "/diff").then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "pat", roles = "PUBLISHER")
    void assetHistoryListsEveryVersionNewestFirstWithAGeometrySummary() throws SQLException {
        String v1 = publishedId();
        List<String> two = twoTractors(v1);
        String draft = draftFromPublished();
        move(draft, two.get(0));
        publish(draft);

        given().get("/api/v1/assets/" + two.get(0) + "/history")
                .then()
                .statusCode(200)
                .body("versionNo", org.hamcrest.Matchers.contains(2, 1))
                .body("status", org.hamcrest.Matchers.contains("PUBLISHED", "SUPERSEDED"))
                .body("[0].geometry", containsString("Polygon, "))
                .body("[0].changedBy", org.hamcrest.Matchers.notNullValue())
                .body("[0].changedAt", org.hamcrest.Matchers.notNullValue())
                .body("[0].name", org.hamcrest.Matchers.notNullValue());
        given().get("/api/v1/assets/" + UUID.randomUUID() + "/history").then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "pat", roles = "PUBLISHER")
    void diffOfADraftWithOneMovedAndOneDeletedAssetListsExactlyThoseTwo() throws SQLException {
        String v1 = publishedId();
        String draft = draftFromPublished();
        List<String> two = twoTractors(draft);
        move(draft, two.get(0));
        remove(draft, two.get(1));

        given().get("/api/v1/layouts/" + draft + "/diff?against=" + v1)
                .then()
                .statusCode(200)
                .body("added", empty())
                .body("removed", hasSize(1))
                .body("removed[0].assetId", equalTo(two.get(1)))
                .body("changed", hasSize(1))
                .body("changed[0].assetId", equalTo(two.get(0)))
                .body("changed[0].fields", org.hamcrest.Matchers.contains("geometry"));
        // seen from the other side the deleted asset is added
        given().get("/api/v1/layouts/" + v1 + "/diff?against=" + draft)
                .then()
                .body("added[0].assetId", equalTo(two.get(1)))
                .body("removed", empty());
        // without against the published version is the other side
        given().get("/api/v1/layouts/" + draft + "/diff").then().body("changed", hasSize(1));
        given().get("/api/v1/layouts/" + v1 + "/diff?against=" + v1)
                .then()
                .body("added", empty())
                .body("removed", empty())
                .body("changed", empty());
        given().get("/api/v1/layouts/" + draft + "/diff?against=nonsense")
                .then()
                .statusCode(400);
    }

    @Test
    @TestSecurity(user = "pat", roles = "ADMIN")
    void reviewerListHasPublishersAndAdminsButNotTheCaller() throws SQLException {
        given().get("/api/v1/reviewers").then().statusCode(200).body("username", org.hamcrest.Matchers.hasItem("rita"));
        given().get("/api/v1/reviewers")
                .then()
                .body("username", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("vera")))
                .body("username", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("pat")));
    }
}

package com.djavafactory.castor.core.operations.api;

import java.util.List;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.contracts.CanonicalEvent;
import com.djavafactory.castor.core.operations.application.CanonicalEventProcessor;
import com.djavafactory.castor.core.support.ProjectionTestSupport;
import com.djavafactory.castor.core.support.ProjectorHarness;

import static com.djavafactory.castor.core.support.ProjectionTestSupport.count;
import static com.djavafactory.castor.core.support.ProjectionTestSupport.lines;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Data quality API: the review items, resolving them, the dead letters and their replay; who may do what. */
@QuarkusTest
class DataQualityIT {

    private static final String BASE = "/api/v1/ports/westports";
    private static final String LOT = "itap:COIL-3";

    @Inject
    DataSource dataSource;

    @Inject
    CanonicalEventProcessor processor;

    private ProjectorHarness h;

    @BeforeEach
    void setUp() {
        h = new ProjectorHarness(dataSource, processor);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private long openStaleItem() {
        h.clock.step(600);
        h.process(h.events.position(h.layout.crane(), ProjectionTestSupport.LON, ProjectionTestSupport.LAT, null, 2));
        // a back-dated position: older than the one the machine already has
        CanonicalEvent backDated = h.events.at(
                h.events.position(h.layout.crane(), ProjectionTestSupport.LON, ProjectionTestSupport.LAT, null, 2),
                ProjectorHarness.T0);
        h.process(backDated);
        return count(dataSource, "select id from ops.reconciliation_item where kind = 'STALE_EVENT'");
    }

    private long deadLetterForAMovementOfAnUnknownLot() {
        h.clock.step(60);
        h.process(h.events.lotMoved(LOT, h.layout.berth(), h.layout.yard(), 5, "PIECES"));
        return count(dataSource, "select id from ops.dead_letter");
    }

    private void registerLot() {
        h.clock.step(60);
        h.process(h.events.lotRegistered(LOT, "BREAKBULK", "COIL", "PIECES", 60, null));
    }

    // --- review items ---

    @Test
    @TestSecurity(user = "olivia", roles = "OPERATOR")
    void aBackDatedPositionOpensAStaleEventThatAnOperatorResolvesWithANoteAndItIsAudited() {
        long id = openStaleItem();

        given().get(BASE + "/reconciliation-items?status=OPEN")
                .then()
                .statusCode(200)
                .body("", hasSize(1))
                .body("[0].id", equalTo((int) id))
                .body("[0].kind", equalTo("STALE_EVENT"))
                .body("[0].entityType", equalTo("equipment"))
                .body("[0].entityId", equalTo(h.layout.crane().toString()))
                .body("[0].eventType", equalTo("equipment.position.observed"))
                .body("[0].status", equalTo("OPEN"))
                .body("[0].createdAt", notNullValue())
                .body("[0].detail.currentSource", equalTo("SENSORS"));

        given().contentType(ContentType.JSON)
                .body("{\"note\":\"the sensor clock was behind\"}")
                .post(BASE + "/reconciliation-items/" + id + "/resolve")
                .then()
                .statusCode(200)
                .body("status", equalTo("RESOLVED"))
                .body("resolvedBy", equalTo("olivia"))
                .body("note", equalTo("the sensor clock was behind"));

        given().get(BASE + "/reconciliation-items?status=OPEN").then().body("", hasSize(0));
        given().get(BASE + "/reconciliation-items?status=RESOLVED").then().body("", hasSize(1));
        assertEquals(
                List.of("olivia RECONCILIATION_RESOLVED " + id),
                lines(
                        dataSource,
                        "select actor || ' ' || action || ' ' || entity_id from audit.audit_log "
                                + "where action = 'RECONCILIATION_RESOLVED'"));
        // resolving records the decision only: the machine keeps the position it had
        assertEquals(0, count(dataSource, "select version from ops.equipment_state"));
    }

    @Test
    @TestSecurity(user = "olivia", roles = "OPERATOR")
    void resolvingNeedsANoteAndAnItemCanBeResolvedOnce() {
        long id = openStaleItem();
        String path = BASE + "/reconciliation-items/" + id + "/resolve";

        given().contentType(ContentType.JSON)
                .body("{\"note\":\"  \"}")
                .post(path)
                .then()
                .statusCode(400);
        given().contentType(ContentType.JSON).body("{}").post(path).then().statusCode(400);
        given().contentType(ContentType.JSON)
                .body("{\"note\":\"ok\"}")
                .post(BASE + "/reconciliation-items/999999/resolve")
                .then()
                .statusCode(404);
        given().contentType(ContentType.JSON)
                .body("{\"note\":\"ok\"}")
                .post(path)
                .then()
                .statusCode(200);
        given().contentType(ContentType.JSON)
                .body("{\"note\":\"again\"}")
                .post(path)
                .then()
                .statusCode(409);

        assertEquals(
                1, count(dataSource, "select count(*) from audit.audit_log where action = 'RECONCILIATION_RESOLVED'"));
    }

    @Test
    @TestSecurity(user = "olivia", roles = "OPERATOR")
    void theListCanBeNarrowedToOneKind() {
        openStaleItem();
        h.process(h.events.inventoryAdjusted(h.layout.yard(), "COIL", 1, "PIECES", "count"));
        h.process(h.events.lotRegistered(LOT, "BREAKBULK", "COIL", "PIECES", 60, null));
        h.clock.step(10);
        h.process(h.events.lotMoved(LOT, h.layout.yard(), h.layout.berth(), 5, "PIECES"));

        given().get(BASE + "/reconciliation-items?kind=NEGATIVE_BALANCE")
                .then()
                .body("", hasSize(1))
                .body("[0].kind", equalTo("NEGATIVE_BALANCE"));
        given().get(BASE + "/reconciliation-items").then().body("", hasSize(2));
        given().get(BASE + "/reconciliation-items?kind=NOPE").then().statusCode(400);
    }

    // --- dead letters ---

    @Test
    @TestSecurity(user = "ada", roles = "ADMIN")
    void aDeadLetterReplayedAfterItsCauseIsFixedIsProcessedAndTheOriginalStaysLinked() {
        long original = deadLetterForAMovementOfAnUnknownLot();
        String before = lines(dataSource, "select id || error || attempts || body::text from ops.dead_letter")
                .get(0);

        given().get(BASE + "/dead-letters")
                .then()
                .statusCode(200)
                .body("", hasSize(1))
                .body("[0].id", equalTo((int) original))
                .body("[0].reason", equalTo("UNKNOWN_ENTITY"))
                .body("[0].state", equalTo("OPEN"))
                .body("[0].type", equalTo("cargo.lot.moved"))
                .body("[0].replays", equalTo(0));

        registerLot();

        given().post(BASE + "/dead-letters/" + original + "/replay")
                .then()
                .statusCode(200)
                .body("outcome", equalTo("APPLIED"))
                .body("attempt.replayOf", equalTo((int) original))
                .body("attempt.reason", equalTo("REPLAYED"));

        assertEquals(
                "5.000",
                lines(dataSource, "select quantity::text from ops.storage_inventory")
                        .get(0));
        assertEquals(1, count(dataSource, "select count(*) from ops.cargo_movement"));
        // the original is untouched, and a second row links back to it
        assertEquals(
                List.of(before),
                lines(
                        dataSource,
                        "select id || error || attempts || body::text from ops.dead_letter where replay_of is null"));
        assertEquals(
                List.of(String.valueOf(original)),
                lines(dataSource, "select replay_of::text from ops.dead_letter where replay_of is not null"));
        given().get(BASE + "/dead-letters")
                .then()
                .body("", hasSize(1))
                .body("[0].state", equalTo("REPLAYED"))
                .body("[0].replays", equalTo(1));
        given().get(BASE + "/dead-letters/" + original)
                .then()
                .body("replayAttempts", hasSize(1))
                .body("replayAttempts[0].replayOf", equalTo((int) original));
        assertEquals(
                List.of("ada DEAD_LETTER_REPLAYED " + original),
                lines(
                        dataSource,
                        "select actor || ' ' || action || ' ' || entity_id from audit.audit_log "
                                + "where action = 'DEAD_LETTER_REPLAYED'"));
    }

    @Test
    @TestSecurity(user = "ada", roles = "ADMIN")
    void aReplayWhoseCauseIsNotFixedFailsAgainAsANewLinkedAttemptAndAReplayOfAnAppliedEventChangesNothing() {
        long original = deadLetterForAMovementOfAnUnknownLot();

        given().post(BASE + "/dead-letters/" + original + "/replay")
                .then()
                .statusCode(200)
                .body("outcome", equalTo("FAILED"))
                .body("attempt.reason", equalTo("UNKNOWN_ENTITY"))
                .body("attempt.replayOf", equalTo((int) original));
        assertEquals(2, count(dataSource, "select count(*) from ops.dead_letter"));
        given().get(BASE + "/dead-letters").then().body("[0].state", equalTo("REPLAY_FAILED"));

        registerLot();
        given().post(BASE + "/dead-letters/" + original + "/replay").then().body("outcome", equalTo("APPLIED"));
        given().post(BASE + "/dead-letters/" + original + "/replay").then().body("outcome", equalTo("ALREADY_APPLIED"));

        assertEquals(
                "5.000",
                lines(dataSource, "select quantity::text from ops.storage_inventory")
                        .get(0));
        assertEquals(1, count(dataSource, "select count(*) from ops.cargo_movement"));
        assertEquals(4, count(dataSource, "select count(*) from ops.dead_letter"));
        assertEquals(
                3, count(dataSource, "select count(*) from audit.audit_log where action = 'DEAD_LETTER_REPLAYED'"));
    }

    @Test
    @TestSecurity(user = "ada", roles = "ADMIN")
    void aReplayOfAnAttemptOrAnUnknownLetterIsNotFound() {
        long original = deadLetterForAMovementOfAnUnknownLot();
        given().post(BASE + "/dead-letters/999999/replay").then().statusCode(404);
        given().post(BASE + "/dead-letters/" + original + "/replay").then().statusCode(200);
        long attempt = count(dataSource, "select id from ops.dead_letter where replay_of is not null");
        given().post(BASE + "/dead-letters/" + attempt + "/replay").then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "olivia", roles = "OPERATOR")
    void anOperatorSeesTheDeadLettersButCannotReplayThem() {
        long original = deadLetterForAMovementOfAnUnknownLot();

        given().get(BASE + "/dead-letters").then().statusCode(200).body("", hasSize(1));
        given().post(BASE + "/dead-letters/" + original + "/replay").then().statusCode(403);
        assertEquals(1, count(dataSource, "select count(*) from ops.dead_letter"));
    }

    // --- who may ---

    @Test
    @TestSecurity(user = "vera", roles = "VIEWER")
    void aViewerGets403OnEveryEndpoint() {
        long item = openStaleItem();
        long letter = deadLetterForAMovementOfAnUnknownLot();

        given().get(BASE + "/reconciliation-items").then().statusCode(403);
        given().contentType(ContentType.JSON)
                .body("{\"note\":\"x\"}")
                .post(BASE + "/reconciliation-items/" + item + "/resolve")
                .then()
                .statusCode(403);
        given().get(BASE + "/dead-letters").then().statusCode(403);
        given().get(BASE + "/dead-letters/" + letter).then().statusCode(403);
        given().post(BASE + "/dead-letters/" + letter + "/replay").then().statusCode(403);
        assertEquals(0, count(dataSource, "select count(*) from ops.reconciliation_item where status = 'RESOLVED'"));
        assertEquals(0, count(dataSource, "select count(*) from audit.audit_log where action like '%RESOLVED'"));
    }

    @Test
    void nobodyWhoIsNotSignedInGetsAnything() {
        given().get(BASE + "/reconciliation-items").then().statusCode(401);
        given().get(BASE + "/dead-letters").then().statusCode(401);
    }
}

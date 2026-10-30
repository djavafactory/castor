package com.djavafactory.castor.gateway.admin.application;

import java.util.Set;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.gateway.support.AdminSupport;
import com.djavafactory.castor.gateway.support.Db;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Replay of quarantined records: a new inbox row linked to the original, which stays as it was. A still-broken record
 * gives a second linked attempt; once the cause is fixed the replay is published to the canonical topic.
 */
@QuarkusTest
class ReplayIT extends AdminSupport {

    private static final String TS = "2026-10-29T09:00:00Z";

    private long inboxRows() {
        return Db.count(dataSource, "select count(*) from ingest.inbox");
    }

    private long quarantineRows() {
        return Db.count(dataSource, "select count(*) from ingest.quarantine");
    }

    private String quarantineSnapshot(String receptionId) {
        return Db.value(
                dataSource,
                "select i.status || '|' || i.raw::text || '|' || q.id || '|' || q.reason_code || '|' || q.detail || '|' "
                        + "|| q.created_at from ingest.inbox i join ingest.quarantine q using (reception_id) "
                        + "where i.reception_id = ?::uuid",
                receptionId);
    }

    @Test
    void replayOfAStillBrokenRecordCreatesASecondLinkedAttemptAndDeletesNothing() {
        String original = quarantinedGps("GPS-NOBODY", TS);
        String before = quarantineSnapshot(original);
        assertEquals("QUARANTINED", inboxStatus(original));

        String replayId = replay(original)
                .then()
                .statusCode(201)
                .body("replayOf", equalTo(original))
                .body("receptionId", matchesPattern("[0-9a-f-]{36}"))
                .extract()
                .path("receptionId");
        normalizer.runOnce();

        assertEquals(2, inboxRows(), "a new row, nothing deleted");
        assertEquals(2, quarantineRows(), "the replay is quarantined as a second attempt");
        assertEquals(before, quarantineSnapshot(original), "the original row and its entry are exactly as they were");
        assertEquals("QUARANTINED", inboxStatus(replayId));
        assertEquals("UNKNOWN_MAPPING", quarantineReason(replayId));
        assertEquals(
                original,
                Db.value(
                        dataSource, "select replay_of::text from ingest.inbox where reception_id = ?::uuid", replayId));
        assertEquals(
                gpsEventId("GPS-NOBODY", TS),
                Db.value(dataSource, "select source_event_id from ingest.inbox where reception_id = ?::uuid", replayId),
                "the replay carries the sourceEventId of the original");
        assertEquals(0, outboxRows());
        admin().get(ADMIN + "/quarantine/" + original)
                .then()
                .statusCode(200)
                .body("attempts", hasSize(1))
                .body("attempts[0].receptionId", equalTo(replayId))
                .body("attempts[0].status", equalTo("QUARANTINED"))
                .body("attempts[0].reasonCode", equalTo("UNKNOWN_MAPPING"))
                .body("entry.replays", is(1))
                .body("entry.latestReplayStatus", equalTo("QUARANTINED"));
    }

    @Test
    void anUnknownMappingThatAnAdminFixesIsPublishedByTheReplayAndTheOriginalStaysLinked() {
        String sourceEventId = gpsEventId("GPS-NEWCRANE", TS);
        String original = quarantinedGps("GPS-NEWCRANE", TS);
        String before = quarantineSnapshot(original);
        admin().get(ADMIN + "/mappings/unresolved")
                .then()
                .statusCode(200)
                .body("items", hasSize(1))
                .body("items[0].source", equalTo("sensors"))
                .body("items[0].entityType", equalTo("EQUIPMENT"))
                .body("items[0].sourceId", equalTo("GPS-NEWCRANE"))
                .body("items[0].reasonCode", equalTo("UNKNOWN_MAPPING"))
                .body("items[0].records", is(1));

        // The lookup cache holds "no row" for a minute: the admin API must clear it, or the replay would fail again
        mapSensor("GPS-NEWCRANE", "asset-new-crane").then().statusCode(201);
        String replayId = replay(original).then().statusCode(201).extract().path("receptionId");
        normalizer.runOnce();
        relay.runOnce();

        assertEquals("NORMALIZED", inboxStatus(replayId));
        assertEquals(Set.of(sourceEventId), kafkaSourceEvents(Set.of(sourceEventId)), "published to the topic");
        assertEquals(
                "asset-new-crane",
                Db.value(
                        dataSource,
                        "select payload ->> 'entityId' from ingest.outbox where reception_id = ?::uuid",
                        replayId));
        assertEquals(before, quarantineSnapshot(original), "the original quarantine row is unchanged");
        assertEquals(1, quarantineRows());
        assertEquals("QUARANTINED", inboxStatus(original));
        admin().get(ADMIN + "/quarantine/" + original)
                .then()
                .body("attempts", hasSize(1))
                .body("attempts[0].receptionId", equalTo(replayId))
                .body("attempts[0].status", equalTo("NORMALIZED"))
                .body("attempts[0].events", is(1))
                .body("attempts[0].published", is(1))
                .body("entry.latestReplayStatus", equalTo("NORMALIZED"));
        admin().get(ADMIN + "/mappings/unresolved").then().body("items", empty());
    }

    @Test
    void aSecondReplayWaitsForTheFirstToBeProcessed() {
        String original = quarantinedGps("GPS-NOBODY", TS);
        String first = replay(original).then().statusCode(201).extract().path("receptionId");

        replay(original)
                .then()
                .statusCode(409)
                .contentType("application/problem+json")
                .body("errors[0].code", equalTo("REPLAY_PENDING"))
                .body("detail", containsString(first));
        assertEquals(2, inboxRows());

        normalizer.runOnce();
        replay(original).then().statusCode(201);
        assertEquals(3, inboxRows());
    }

    @Test
    void replayingAFailedReplayLinksTheNewAttemptToTheOriginalNotToTheReplay() {
        String original = quarantinedGps("GPS-NOBODY", TS);
        String first = replay(original).then().statusCode(201).extract().path("receptionId");
        normalizer.runOnce();

        replay(first).then().statusCode(201).body("replayOf", equalTo(original));
        normalizer.runOnce();

        assertEquals(2, Db.count(dataSource, "select count(*) from ingest.inbox where replay_of = ?::uuid", original));
        admin().get(ADMIN + "/quarantine/" + first)
                .then()
                .body("entry.replayOf", equalTo(original))
                .body("attempts", hasSize(2));
    }

    @Test
    void aRecordWhosePayloadWasRemovedCannotBeReplayed() {
        String original = quarantinedGps("GPS-NOBODY", TS);
        Db.execute(dataSource, "update ingest.inbox set raw = null where reception_id = ?::uuid", original);

        replay(original).then().statusCode(409).body("errors[0].code", equalTo("RAW_EXPIRED"));
        assertEquals(1, inboxRows());
        admin().get(ADMIN + "/quarantine/" + original)
                .then()
                .statusCode(200)
                .body("rawAvailable", is(false))
                .body("raw", org.hamcrest.Matchers.nullValue());
    }

    @Test
    void onlyAQuarantinedRecordOfThePortCanBeReplayed() {
        String normalized = accept("fine-1");
        normalizer.runOnce();

        replay(normalized).then().statusCode(404).body("errors[0].code", equalTo("NOT_IN_QUARANTINE"));
        replay("00000000-0000-7000-8000-000000000000").then().statusCode(404);
        replay("not-a-uuid").then().statusCode(422).body("errors[0].code", equalTo("INVALID_RECEPTION_ID"));
        String other = quarantinedGps("GPS-NOBODY", TS);
        admin().post("/internal/v1/ports/otherport/quarantine/" + other + "/replay")
                .then()
                .statusCode(404);
        assertEquals(2, inboxRows());
    }

    @Test
    void theSourceStillGetsDuplicateForTheOriginalAfterAReplay() {
        String original = quarantinedGps("GPS-NOBODY", TS);
        replay(original).then().statusCode(201);

        postTo("sensors", gps("GPS-NOBODY", TS))
                .then()
                .statusCode(200)
                .body("receptionId", equalTo(original))
                .body("duplicate", is(true));
        assertEquals(2, inboxRows(), "the repeat stored nothing");
    }

    @Test
    void theListShowsOriginalsAndTheirReplaysNewestFirstWithTheLink() {
        String original = quarantinedGps("GPS-NOBODY", TS);
        String replayId = replay(original).then().statusCode(201).extract().path("receptionId");
        normalizer.runOnce();

        admin().get(ADMIN + "/quarantine")
                .then()
                .statusCode(200)
                .body("items.receptionId", contains(replayId, original))
                .body("items[0].replayOf", equalTo(original))
                .body("items[1].replayOf", org.hamcrest.Matchers.nullValue())
                .body("items[1].replays", is(1))
                .body("items[0].subjectId", equalTo("GPS-NOBODY"))
                .body("items[0].subjectType", equalTo("EQUIPMENT"))
                .body("nextBefore", org.hamcrest.Matchers.nullValue());
        assertNull(Db.value(dataSource, "select replay_of from ingest.inbox where reception_id = ?::uuid", original));
    }
}

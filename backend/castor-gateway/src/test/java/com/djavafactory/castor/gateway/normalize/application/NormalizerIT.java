package com.djavafactory.castor.gateway.normalize.application;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.gateway.normalize.domain.EventIds;
import com.djavafactory.castor.gateway.shared.domain.FailPoint;
import com.djavafactory.castor.gateway.shared.domain.FailPoints;
import com.djavafactory.castor.gateway.support.Db;
import com.djavafactory.castor.gateway.support.Events;
import com.djavafactory.castor.gateway.support.KafkaProbe;
import com.djavafactory.castor.gateway.support.PipelineSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The normalizer: inbox to outbox in one transaction, quarantine, the adapter SPI and deterministic event ids. */
@QuarkusTest
class NormalizerIT extends PipelineSupport {

    private long outboxRows() {
        return Db.count(dataSource, "select count(*) from ingest.outbox");
    }

    private String quarantineReason(String receptionId) {
        return Db.value(
                dataSource, "select reason_code from ingest.quarantine where reception_id = ?::uuid", receptionId);
    }

    @Test
    void aCanonicalRecordBecomesOneOutboxRowAndTheInboxRowIsNormalized() {
        String sourceEventId = Events.newSourceEventId();
        String receptionId = accept(sourceEventId);

        assertEquals(1, normalizer.runOnce());

        assertEquals("NORMALIZED", inboxStatus(receptionId));
        assertEquals(
                Timestamp.from(clock.instant()),
                Db.value(
                        dataSource,
                        "select normalized_at from ingest.inbox where reception_id = ?::uuid",
                        receptionId));
        assertEquals(1, outboxRows());
        UUID reception = UUID.fromString(receptionId);
        assertEquals(
                EventIds.of(reception, 0).toString(),
                Db.value(
                        dataSource,
                        "select event_id::text from ingest.outbox where reception_id = ?::uuid",
                        receptionId));
        assertEquals("crane-mhc-2", Db.value(dataSource, "select aggregate_id from ingest.outbox"));
        assertEquals("equipment.status.changed", Db.value(dataSource, "select event_type from ingest.outbox"));
        assertNull(Db.value(dataSource, "select published_at from ingest.outbox"));
        assertEquals(0, Db.count(dataSource, "select count(*) from ingest.quarantine"));
        // The gateway's own fields are in the stored event
        assertEquals(receptionId, Db.value(dataSource, "select payload ->> 'receptionId' from ingest.outbox"));
        assertEquals("westports", Db.value(dataSource, "select payload ->> 'portId' from ingest.outbox"));
        assertEquals(sourceEventId, Db.value(dataSource, "select payload ->> 'sourceEventId' from ingest.outbox"));
        assertEquals(0, normalizer.runOnce(), "nothing is left to do");
    }

    @Test
    void anInvalidCanonicalEnvelopeEndsQuarantinedWithSchemaInvalidAndIsNotPublished() {
        String sourceEventId = Events.newSourceEventId();
        String receptionId = post(Events.invalidStatus(sourceEventId))
                .then()
                .statusCode(202)
                .extract()
                .path("receptionId");

        assertEquals(1, normalizer.runOnce());
        relay.runOnce();

        assertEquals("QUARANTINED", inboxStatus(receptionId));
        assertEquals("SCHEMA_INVALID", quarantineReason(receptionId));
        String detail = Db.value(dataSource, "select detail from ingest.quarantine");
        assertTrue(detail.contains("/payload/status"), detail);
        assertEquals(0, outboxRows());
        assertTrue(KafkaProbe.forSourceEvents(bootstrapServers, Set.of(sourceEventId))
                .isEmpty());
    }

    @Test
    void aRecordThatIsNotACanonicalEventIsQuarantinedToo() {
        // Passes intake (sourceEventId and occurredAt), but is not an envelope at all
        String receptionId = post("{\"sourceEventId\":\"" + Events.newSourceEventId()
                        + "\",\"occurredAt\":\"2026-10-29T08:20:00Z\",\"hello\":\"world\"}")
                .then()
                .statusCode(202)
                .extract()
                .path("receptionId");

        normalizer.runOnce();

        assertEquals("QUARANTINED", inboxStatus(receptionId));
        assertEquals("SCHEMA_INVALID", quarantineReason(receptionId));
    }

    @Test
    void anEventForAnotherPortIsQuarantinedWithPortMismatch() {
        String body = Events.status(Events.newSourceEventId())
                .replace("\"schemaVersion\": 1,", "\"schemaVersion\": 1, \"portId\": \"other\",");
        String receptionId = post(body).then().statusCode(202).extract().path("receptionId");

        normalizer.runOnce();

        assertEquals("PORT_MISMATCH", quarantineReason(receptionId));
        assertEquals(0, outboxRows());
    }

    @Test
    void aSourceWithoutAnAdapterIsQuarantinedWithNoAdapter() {
        UUID id = insertInbox("nosuch", "nosuch-1", "{\"anything\":1}");

        normalizer.runOnce();

        assertEquals("QUARANTINED", inboxStatus(id.toString()));
        assertEquals("NO_ADAPTER", quarantineReason(id.toString()));
        assertEquals(
                "No adapter for source nosuch",
                Db.value(dataSource, "select detail from ingest.quarantine where reception_id = ?", id));
    }

    @Test
    void anAdaptersOwnRejectionKeepsItsReasonCode() {
        UUID id = insertInbox("test-faulty", "s-1", "{\"reject\":true}");

        normalizer.runOnce();

        assertEquals("CUSTOM_REASON", quarantineReason(id.toString()));
    }

    @Test
    void anAdapterThatFailsQuarantinesOnlyItsRecordAndHidesTheErrorFromTheDetail() {
        clock.advance(Duration.ofMillis(1));
        UUID bad = insertInbox("test-faulty", "s-bad", "{\"boom\":true}");
        clock.advance(Duration.ofMillis(1));
        UUID good = insertInbox("test-faulty", "s-good", "{}");

        assertEquals(2, normalizer.runOnce());

        assertEquals("QUARANTINED", inboxStatus(bad.toString()));
        assertEquals("ADAPTER_ERROR", quarantineReason(bad.toString()));
        String detail = Db.value(dataSource, "select detail from ingest.quarantine where reception_id = ?", bad);
        assertTrue(detail.contains("errorId"), detail);
        assertTrue(!detail.contains("hunter2"), "the exception text stays in the log: " + detail);
        assertEquals("NORMALIZED", inboxStatus(good.toString()));
        assertEquals(1, outboxRows());
    }

    @Test
    void oneRecordCanMakeSeveralEventsWithIdsFromTheReceptionIdAndIndex() {
        UUID id = insertInbox("test-multi", "gate-batch", "{\"count\":3}");

        normalizer.runOnce();

        List<String> ids = Db.column(
                dataSource, "select event_id::text from ingest.outbox where reception_id = ? order by event_index", id);
        assertEquals(
                List.of(
                        EventIds.of(id, 0).toString(),
                        EventIds.of(id, 1).toString(),
                        EventIds.of(id, 2).toString()),
                ids,
                "whatever eventId the adapter wrote is replaced");
        assertEquals(
                List.of(0, 1, 2), Db.column(dataSource, "select event_index from ingest.outbox order by event_index"));
        assertEquals(
                EventIds.of(id, 1).toString(),
                Db.value(dataSource, "select payload ->> 'eventId' from ingest.outbox where event_index = 1"));
    }

    @Test
    void normalizingARecordAgainGivesTheSameEventId() {
        String receptionId = accept(Events.newSourceEventId());
        normalizer.runOnce();
        String first = Db.value(dataSource, "select event_id::text from ingest.outbox");

        // As if the first transaction had never committed
        Db.execute(dataSource, "delete from ingest.outbox");
        Db.execute(
                dataSource,
                "update ingest.inbox set status = 'RECEIVED', normalized_at = null where reception_id = ?::uuid",
                receptionId);
        normalizer.runOnce();

        assertEquals(first, Db.value(dataSource, "select event_id::text from ingest.outbox"));
        assertEquals(1, outboxRows());
    }

    @Test
    void aBatchIsOneTransactionIfAnyOutboxWriteFailsNothingOfTheBatchIsKept() {
        clock.advance(Duration.ofMillis(1));
        String first = accept(Events.newSourceEventId());
        clock.advance(Duration.ofMillis(1));
        String second = accept(Events.newSourceEventId());
        // An outbox row that already owns the eventId of the second record: its insert violates the unique key
        UUID other = insertInbox("canonical", "other", "{}");
        Db.execute(dataSource, "update ingest.inbox set status = 'NORMALIZED' where reception_id = ?", other);
        Db.execute(
                dataSource,
                "insert into ingest.outbox (reception_id, event_index, event_id, port_id, aggregate_id, event_type, payload, created_at) "
                        + "values (?, 0, ?, 'westports', 'x', 'x', '{}', now())",
                other,
                EventIds.of(UUID.fromString(second), 0));

        assertThrows(RuntimeException.class, normalizer::runOnce);

        assertEquals("RECEIVED", inboxStatus(first), "the first record's work was rolled back with the second");
        assertEquals("RECEIVED", inboxStatus(second));
        assertEquals(1, outboxRows(), "only the pre-existing row");
        assertEquals(0, Db.count(dataSource, "select count(*) from ingest.quarantine"));
    }

    @Test
    void batchesHold200RecordsAndTheOldestGoFirst() {
        // 450 compact events in one request: they keep the order of the array
        String body = IntStream.range(0, 450)
                .mapToObj(i ->
                        Events.status("batch-" + i + "-" + UUID.randomUUID()).replaceAll("\\s+", ""))
                .collect(Collectors.joining(",", "[", "]"));
        post(body).then().statusCode(207);

        assertEquals(200, normalizer.runOnce());
        List<String> firstRun = Db.column(
                dataSource,
                "select source_event_id from ingest.inbox where status = 'NORMALIZED' order by reception_id");
        for (int i = 0; i < 200; i++) {
            assertTrue(firstRun.get(i).startsWith("batch-" + i + "-"), "run 1, position " + i + ": " + firstRun.get(i));
        }

        assertEquals(200, normalizer.runOnce());
        assertEquals(50, normalizer.runOnce());
        assertEquals(0, normalizer.runOnce());
        assertEquals(450, outboxRows());
        assertEquals(0, Db.count(dataSource, "select count(*) from ingest.inbox where status <> 'NORMALIZED'"));
    }

    @Test
    void rowsAnotherWorkerHoldsAreSkippedNotWaitedFor() throws Exception {
        clock.advance(Duration.ofMillis(1));
        String held = accept(Events.newSourceEventId());
        clock.advance(Duration.ofMillis(1));
        String free = accept(Events.newSourceEventId());

        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (var lock =
                    other.prepareStatement("select 1 from ingest.inbox where reception_id = ?::uuid for update")) {
                lock.setString(1, held);
                lock.executeQuery().close();

                assertEquals(1, normalizer.runOnce(), "the free row is taken, the held one skipped without waiting");
                assertEquals("RECEIVED", inboxStatus(held));
                assertEquals("NORMALIZED", inboxStatus(free));
            } finally {
                other.rollback();
            }
        }

        assertEquals(1, normalizer.runOnce());
        assertEquals("NORMALIZED", inboxStatus(held));
    }

    @Test
    void theFailPointBeforeNormalizeStopsTheRunAndLosesNothing() {
        String receptionId = accept(Events.newSourceEventId());
        failPoints.arm(FailPoint.BEFORE_NORMALIZE);

        assertThrows(FailPoints.FailPointException.class, normalizer::runOnce);

        assertEquals("RECEIVED", inboxStatus(receptionId));
        assertEquals(0, outboxRows());

        failPoints.disarm();
        assertEquals(1, normalizer.runOnce());
        assertEquals("NORMALIZED", inboxStatus(receptionId));
    }
}

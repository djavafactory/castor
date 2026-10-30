package com.djavafactory.castor.gateway.mapping.application;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.gateway.support.Db;
import com.djavafactory.castor.gateway.support.MappingSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Identity mapping end to end on the real database: a record posted to a system source is mapped valid at its
 * occurredAt, and one the gateway cannot map safely is quarantined with its reason and never reaches Kafka.
 */
@QuarkusTest
class MappingResolutionIT extends MappingSupport {

    private static String gps(String sensorId, String ts) {
        return """
                {"sensorId":"%s","kind":"GPS","values":{"lon":101.3,"lat":2.98,"accuracyM":2.5},"ts":"%s"}""".formatted(sensorId, ts);
    }

    private static String sourceEventId(String sensorId, String ts) {
        return sensorId + ":GPS:" + ts;
    }

    private String entityIdOfEventAt(String sensorId, String ts) {
        return Db.value(
                dataSource,
                "select payload ->> 'entityId' from ingest.outbox o join ingest.inbox i using (reception_id) "
                        + "where i.source_event_id = ?",
                sourceEventId(sensorId, ts));
    }

    @Test
    void aCraneRemappedAtTenMapsAnEventAtNineFiftyNineToTheOldAssetAndAtTenOhOneToTheNewOne() {
        Instant ten = Instant.parse("2026-10-29T10:00:00Z");
        mapAsset("sensors", "EQUIPMENT", "GPS-QC1", "asset-old", Instant.EPOCH, ten);
        mapAsset("sensors", "EQUIPMENT", "GPS-QC1", "asset-new", ten, null);
        String before = "2026-10-29T09:59:00Z";
        String after = "2026-10-29T10:01:00Z";
        accepted("sensors", gps("GPS-QC1", before));
        accepted("sensors", gps("GPS-QC1", after));

        assertEquals(2, normalizer.runOnce());

        assertEquals("asset-old", entityIdOfEventAt("GPS-QC1", before));
        assertEquals("asset-new", entityIdOfEventAt("GPS-QC1", after));
        assertEquals(0, Db.count(dataSource, "select count(*) from ingest.quarantine"));
    }

    @Test
    void theRemappingInstantBelongsToTheNewAsset() {
        Instant ten = Instant.parse("2026-10-29T10:00:00Z");
        mapAsset("sensors", "EQUIPMENT", "GPS-QC1", "asset-old", Instant.EPOCH, ten);
        mapAsset("sensors", "EQUIPMENT", "GPS-QC1", "asset-new", ten, null);
        accepted("sensors", gps("GPS-QC1", "2026-10-29T10:00:00Z"));
        accepted("sensors", gps("GPS-QC1", "2026-10-29T09:59:59.999Z"));

        normalizer.runOnce();

        assertEquals("asset-new", entityIdOfEventAt("GPS-QC1", "2026-10-29T10:00:00Z"));
        assertEquals("asset-old", entityIdOfEventAt("GPS-QC1", "2026-10-29T09:59:59.999Z"));
    }

    @Test
    void aSourceIdWithNoRowIsQuarantinedAsUnknownMappingAndNothingIsPublished() {
        String ts = "2026-10-29T09:00:00Z";
        String receptionId = accepted("sensors", gps("GPS-NOBODY", ts));

        normalizer.runOnce();
        relay.runOnce();

        assertEquals("QUARANTINED", inboxStatus(receptionId));
        assertEquals("UNKNOWN_MAPPING", quarantineReason(receptionId));
        assertEquals(0, outboxRows());
        assertEquals(Set.of(), kafkaSourceEvents(Set.of(sourceEventId("GPS-NOBODY", ts))));
    }

    @Test
    void aMappingThatStartsAfterTheEventDoesNotApplyToIt() {
        mapAsset("sensors", "EQUIPMENT", "GPS-QC2", "asset-qc2", Instant.parse("2026-10-29T10:00:00Z"), null);
        String receptionId = accepted("sensors", gps("GPS-QC2", "2026-10-29T09:00:00Z"));

        normalizer.runOnce();

        assertEquals("UNKNOWN_MAPPING", quarantineReason(receptionId));
    }

    @Test
    void theDatabaseNeverHoldsTwoRowsThatApplyAtTheSameTime() {
        mapAsset("sensors", "EQUIPMENT", "GPS-QC3", "asset-a", Instant.EPOCH, null);

        var failure = assertThrows(
                IllegalStateException.class,
                () -> mapAsset(
                        "sensors", "EQUIPMENT", "GPS-QC3", "asset-b", Instant.parse("2026-10-29T10:00:00Z"), null));

        assertEquals("23P01", ((SQLException) failure.getCause()).getSQLState(), "exclusion violation");
    }

    @Test
    void ifTwoRowsDidApplyTheRecordIsQuarantinedAsAmbiguousNotResolvedByLuck() {
        Db.execute(dataSource, "alter table ingest.identity_mapping drop constraint identity_mapping_no_overlap");
        try {
            mapAsset("sensors", "EQUIPMENT", "GPS-QC4", "asset-a", Instant.EPOCH, null);
            mapAsset("sensors", "EQUIPMENT", "GPS-QC4", "asset-b", Instant.EPOCH, null);
            String receptionId = accepted("sensors", gps("GPS-QC4", "2026-10-29T09:00:00Z"));

            normalizer.runOnce();

            assertEquals("AMBIGUOUS_MAPPING", quarantineReason(receptionId));
            assertEquals(0, outboxRows());
        } finally {
            Db.execute(dataSource, "delete from ingest.identity_mapping");
            Db.execute(
                    dataSource,
                    "alter table ingest.identity_mapping add constraint identity_mapping_no_overlap exclude using gist ("
                            + "port_id with =, source_system with =, source_entity_type with =, source_id with =, "
                            + "tstzrange(effective_from, effective_to) with &&)");
        }
    }

    @Test
    void theRowsOfASourceIdAreReadOnceAMinute() {
        String ts = "2026-10-29T09:00:00Z";
        String first = accepted("sensors", gps("GPS-QC5", ts));
        normalizer.runOnce();
        assertEquals("UNKNOWN_MAPPING", quarantineReason(first));

        // inserted straight into the table, so the cache is not told
        Db.execute(
                dataSource,
                "insert into ingest.identity_mapping (port_id, source_system, source_entity_type, source_id, "
                        + "canonical_id, effective_from, created_by, created_at) "
                        + "values ('westports', 'sensors', 'EQUIPMENT', 'GPS-QC5', 'asset-qc5', 'epoch', 'test', now())");
        String second = accepted("sensors", gps("GPS-QC5", "2026-10-29T09:00:01Z"));
        normalizer.runOnce();
        assertEquals("UNKNOWN_MAPPING", quarantineReason(second), "the miss is still cached");

        clock.advance(Duration.ofSeconds(61));
        String third = accepted("sensors", gps("GPS-QC5", "2026-10-29T09:00:02Z"));
        normalizer.runOnce();

        assertEquals("NORMALIZED", inboxStatus(third));
        assertEquals("asset-qc5", entityIdOfEventAt("GPS-QC5", "2026-10-29T09:00:02Z"));
    }

    @Test
    void lotsAndTruckVisitsAreCreatedBySourcesAndNeedNoMappingRow() {
        String lot = accepted("itap", """
                {"txnId":"T-77","lotRef":"L-77","family":"DRY_BULK","commodity":"COAL","qty":10,"uom":"t",
                 "ts":"2026-10-29T09:00:00Z"}""");
        mapAsset("scss", "GATE", "G1", "asset-g1");
        String visit = accepted("scss", """
                {"truckVisit":"V-77","gateRef":"G1","direction":"IN","lane":"1","ts":"2026-10-29T09:00:00Z"}""");

        assertEquals(2, normalizer.runOnce());

        assertEquals("NORMALIZED", inboxStatus(lot));
        assertEquals("NORMALIZED", inboxStatus(visit));
        assertEquals(
                "itap:L-77",
                Db.value(
                        dataSource, "select payload ->> 'entityId' from ingest.outbox where event_type like 'cargo%'"));
        assertEquals(
                "scss:V-77",
                Db.value(dataSource, "select payload ->> 'entityId' from ingest.outbox where event_type like 'gate%'"));
    }

    @Test
    void aMappedBerthAndAnEdiCallRefAreResolvedForTheirSources() {
        mapAsset("cbas", "BERTH", "B3", "asset-berth-3");
        mapAsset("edic", "VESSEL_CALL", "EDI-1", "MV-ONE-1");
        accepted("cbas", """
                {"msgId":"M-1","callRef":"MV-ONE-1","vessel":{"name":"MV One","loa":100,"beam":20},"berthRef":"B3",
                 "status":"ALONGSIDE","eta":"2026-10-29T06:00:00Z","ata":"2026-10-29T06:20:00Z"}""");
        postText("edic", "MSG+E-1'CALL+EDI-1'EVT+DEPARTED'DTM+202610291400'")
                .then()
                .statusCode(202);

        assertEquals(2, normalizer.runOnce());

        assertEquals(
                "asset-berth-3",
                Db.value(
                        dataSource,
                        "select payload #>> '{payload,berthId}' from ingest.outbox where aggregate_id = 'MV-ONE-1' order by id limit 1"));
        assertEquals(2, Db.count(dataSource, "select count(*) from ingest.outbox where aggregate_id = 'MV-ONE-1'"));
        assertEquals(
                "2026-10-29T14:00:00Z",
                Db.value(
                        dataSource,
                        "select payload #>> '{payload,atd}' from ingest.outbox where payload #>> '{payload,atd}' is not null"));
    }
}

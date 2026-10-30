package com.djavafactory.castor.core.operations.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.contracts.CanonicalEvent;
import com.djavafactory.castor.contracts.Topics;
import com.djavafactory.castor.core.support.KafkaTestClient;
import com.djavafactory.castor.core.support.ProjectionTestSupport;
import com.djavafactory.castor.core.support.ProjectionTestSupport.Layout;
import com.djavafactory.castor.core.support.TestEvents;
import com.djavafactory.castor.core.support.TestEvents.SteppedClock;
import com.djavafactory.castor.core.support.TestProjectorFaults;

import static com.djavafactory.castor.core.support.ProjectionTestSupport.count;
import static com.djavafactory.castor.core.support.ProjectionTestSupport.lines;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An event that cannot be applied is set aside with its reason, and never holds up the events behind it. */
@QuarkusTest
class DeadLetterIT {

    private static final Instant T0 = Instant.parse("2026-10-30T08:00:00Z");
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    @Inject
    DataSource dataSource;

    @Inject
    CanonicalEventProcessor processor;

    @Inject
    ObjectMapper json;

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String bootstrapServers;

    private Layout layout;
    private TestEvents events;
    private long offset;

    @BeforeEach
    void setUp() {
        layout = ProjectionTestSupport.publishedLayout(dataSource, 1);
        events = new TestEvents(new SteppedClock(T0));
        offset = 0;
    }

    @AfterEach
    void tearDown() {
        TestProjectorFaults.reset();
        ProjectionTestSupport.clearState(dataSource);
    }

    private void process(byte[] value) {
        processor.process(new IncomingRecord(Topics.INGEST_CANONICAL, 3, offset++, "key", value));
    }

    private List<String> deadLetters() {
        return lines(
                dataSource,
                "select substring(error from '^[A-Z_]+') || ' ' || attempts || ' ' || coalesce(port_id::text, '-') "
                        + "|| ' ' || partition || ' ' || coalesce(event_id::text, '-') from ops.dead_letter order by id");
    }

    @Test
    void aPoisonEventIsDeadLetteredAfterThreeAttemptsAndTheNextEventOnItsPartitionIsApplied() throws Exception {
        CanonicalEvent poison = events.status(layout.crane(), "WORKING", null);
        CanonicalEvent next =
                events.position(layout.crane(), ProjectionTestSupport.LON, ProjectionTestSupport.LAT, null, 2);
        TestProjectorFaults.failBeforeHandle(e -> e.eventId().equals(poison.eventId()));

        new KafkaTestClient(bootstrapServers).publish(Topics.INGEST_CANONICAL, List.of(poison, next));

        await().atMost(Duration.ofSeconds(60))
                .until(() -> count(dataSource, "select count(*) from ops.equipment_state") == 1);
        assertEquals(
                1, count(dataSource, "select count(*) from ops.dead_letter"), "one dead letter, however many attempts");
        List<String> row = lines(
                dataSource,
                "select error || '|' || attempts || '|' || topic || '|' || event_id || '|' || port_id "
                        + "from ops.dead_letter");
        String[] cells = row.get(0).split("\\|");
        assertTrue(cells[0].startsWith("PROCESSING_FAILED: IllegalStateException: fail point before the handler"));
        assertEquals("3", cells[1]);
        assertEquals(Topics.INGEST_CANONICAL, cells[2]);
        assertEquals(poison.eventId(), cells[3]);
        assertEquals(layout.port(), Long.parseLong(cells[4]));
        // three attempts at the poison and one at the event behind it
        assertEquals(4, TestProjectorFaults.beforeHandleCalls());
        assertEquals(
                1,
                count(
                        dataSource,
                        "select count(*) from ops.processed_event where event_id = '" + next.eventId() + "'"));
        assertEquals(
                0,
                count(
                        dataSource,
                        "select count(*) from ops.processed_event where event_id = '" + poison.eventId() + "'"));
        assertEquals(
                0,
                count(
                        dataSource,
                        "select count(*) from ops.canonical_event where event_id = '" + poison.eventId() + "'"));

        var dlq = new KafkaTestClient(bootstrapServers)
                .readUntil(
                        Topics.INTEGRATION_DLQ,
                        found -> found.stream().anyMatch(r -> new String(r.value()).contains(poison.eventId())),
                        Duration.ofSeconds(30));
        var message = dlq.stream()
                .filter(r -> new String(r.value()).contains(poison.eventId()))
                .findFirst()
                .orElseThrow();
        Map<String, Object> body = json.readValue(message.value(), OBJECT);
        assertEquals(poison.aggregateId(), message.key());
        assertEquals("PROCESSING_FAILED", body.get("reason"));
        assertEquals(3, body.get("attempts"));
        assertTrue(body.get("cause").toString().contains("fail point"));
        @SuppressWarnings("unchecked")
        var reference = (Map<String, Object>) body.get("reference");
        assertEquals(poison.sourceEventId(), reference.get("sourceEventId"));
        assertEquals("SENSORS", reference.get("sourceSystem"));
        @SuppressWarnings("unchecked")
        var source = (Map<String, Object>) body.get("source");
        assertEquals(Topics.INGEST_CANONICAL, source.get("topic"));
    }

    @Test
    void anEventNamingAnUnknownPortAssetOrTypeIsDeadLetteredWithItsReasonAndNothingElseHappens() {
        CanonicalEvent unknownPort = events.port(events.status(layout.crane(), "IDLE", null), "nowhere");
        CanonicalEvent unknownAsset = events.status(UUID.randomUUID(), "IDLE", null);
        CanonicalEvent unknownType = events.type(events.status(layout.crane(), "IDLE", null), "flow.rate.observed");
        CanonicalEvent madeUpType = events.type(events.status(layout.crane(), "IDLE", null), "made.up");
        CanonicalEvent outside = events.position(layout.crane(), 0.0, 0.0, null, 1);
        CanonicalEvent berthIsNotABerth = events.vesselCall("C1", "ALONGSIDE", layout.pad());

        for (CanonicalEvent e :
                List.of(unknownPort, unknownAsset, unknownType, madeUpType, outside, berthIsNotABerth)) {
            process(TestEvents.bytes(e));
        }

        assertEquals(
                List.of(
                        "UNKNOWN_PORT 1 - 3 " + unknownPort.eventId(),
                        "UNKNOWN_ENTITY 1 " + layout.port() + " 3 " + unknownAsset.eventId(),
                        "UNKNOWN_TYPE 1 " + layout.port() + " 3 " + unknownType.eventId(),
                        "UNKNOWN_TYPE 1 " + layout.port() + " 3 " + madeUpType.eventId(),
                        "OUT_OF_EXTENT 1 " + layout.port() + " 3 " + outside.eventId(),
                        "UNKNOWN_ENTITY 1 " + layout.port() + " 3 " + berthIsNotABerth.eventId()),
                deadLetters());
        assertEquals(0, count(dataSource, "select count(*) from ops.processed_event"));
        assertEquals(0, count(dataSource, "select count(*) from ops.equipment_state"));
        assertEquals(0, count(dataSource, "select count(*) from ops.vessel_call"));
        assertEquals(0, count(dataSource, "select count(*) from ops.state_change"));

        process(TestEvents.bytes(events.status(layout.crane(), "WORKING", null)));
        assertEquals(1, count(dataSource, "select count(*) from ops.equipment_state"));
    }

    @Test
    void typesWithoutAHandlerYetAreUnknownTypeUntilTheyAreRegistered() {
        for (String type : List.of("flow.rate.observed", "truck.approach.observed")) {
            process(TestEvents.bytes(events.type(events.status(layout.crane(), "IDLE", null), type)));
        }

        assertEquals(2, count(dataSource, "select count(*) from ops.dead_letter where error like 'UNKNOWN_TYPE:%'"));
    }

    @Test
    void anUnreadableMessageOrAnInvalidPayloadIsAnInvalidEventWithItsRawBodyKept() {
        process("{ not json".getBytes());
        CanonicalEvent badStatus = events.status(layout.crane(), "DANCING", null);
        process(TestEvents.bytes(badStatus));
        ObjectNode noAccuracy =
                (ObjectNode) events.position(layout.crane(), 1, 1, null, 1).payload();
        noAccuracy.remove("accuracyM");
        CanonicalEvent badPosition = events.withPayload(events.position(layout.crane(), 1, 1, null, 1), noAccuracy);
        process(TestEvents.bytes(badPosition));

        assertEquals(
                List.of(
                        "INVALID_EVENT 1 - 3 -",
                        "INVALID_EVENT 1 " + layout.port() + " 3 " + badStatus.eventId(),
                        "INVALID_EVENT 1 " + layout.port() + " 3 " + badPosition.eventId()),
                deadLetters());
        assertEquals(
                "{ not json",
                lines(dataSource, "select body ->> 'raw' from ops.dead_letter order by id")
                        .get(0));
        assertEquals(
                "DANCING",
                lines(dataSource, "select body -> 'payload' ->> 'status' from ops.dead_letter order by id")
                        .get(1));
    }

    @Test
    void everyDeadLetterIsAlsoSentToTheDlqTopicWithItsCauseAndSourceReference() throws Exception {
        CanonicalEvent unknownAsset = events.status(UUID.randomUUID(), "IDLE", null);

        process(TestEvents.bytes(unknownAsset));

        var dlq = new KafkaTestClient(bootstrapServers)
                .readUntil(
                        Topics.INTEGRATION_DLQ,
                        found -> found.stream().anyMatch(r -> new String(r.value()).contains(unknownAsset.eventId())),
                        Duration.ofSeconds(30));
        var message = dlq.stream()
                .filter(r -> new String(r.value()).contains(unknownAsset.eventId()))
                .findFirst()
                .orElseThrow();
        Map<String, Object> body = json.readValue(message.value(), OBJECT);
        assertEquals("UNKNOWN_ENTITY", body.get("reason"));
        assertTrue(body.get("cause").toString().contains(unknownAsset.entityId()));
        assertEquals(1, body.get("attempts"));
        assertEquals(unknownAsset.aggregateId(), message.key());
        @SuppressWarnings("unchecked")
        var source = (Map<String, Object>) body.get("source");
        assertEquals(3, source.get("partition"));
        assertEquals(0, source.get("offset"));
    }
}

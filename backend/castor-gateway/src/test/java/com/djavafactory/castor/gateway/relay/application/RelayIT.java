package com.djavafactory.castor.gateway.relay.application;

import java.sql.Connection;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.gateway.normalize.application.NormalizeInbox;
import com.djavafactory.castor.gateway.normalize.domain.EventIds;
import com.djavafactory.castor.gateway.normalize.domain.NormalizationStore;
import com.djavafactory.castor.gateway.normalize.domain.SourceAdapter;
import com.djavafactory.castor.gateway.relay.domain.OutboxStore;
import com.djavafactory.castor.gateway.relay.infrastructure.JdbcOutboxStore;
import com.djavafactory.castor.gateway.relay.infrastructure.KafkaEventPublisher;
import com.djavafactory.castor.gateway.shared.domain.FailPoint;
import com.djavafactory.castor.gateway.shared.domain.FailPoints;
import com.djavafactory.castor.gateway.shared.infrastructure.CastorConfig;
import com.djavafactory.castor.gateway.shared.infrastructure.KafkaTopics;
import com.djavafactory.castor.gateway.support.Db;
import com.djavafactory.castor.gateway.support.Events;
import com.djavafactory.castor.gateway.support.KafkaProbe;
import com.djavafactory.castor.gateway.support.KafkaProbe.Message;
import com.djavafactory.castor.gateway.support.PipelineSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The relay on a real broker (the Dev Services one): events reach Kafka once, in order per aggregate, and the three
 * "gateway stopped" moments of PRD 19.2 lose nothing. "Restarted" means new worker objects over the same database and
 * broker, which is all a new process has.
 */
@QuarkusTest
class RelayIT extends PipelineSupport {

    @Inject
    CastorConfig castor;

    @Inject
    KafkaTopics topics;

    @Inject
    OutboxStore outbox;

    @Inject
    NormalizationStore normalizationStore;

    @Inject
    @Any
    Instance<SourceAdapter> adapters;

    @Inject
    MeterRegistry meters;

    private List<Message> onKafka(String... sourceEventIds) {
        return KafkaProbe.forSourceEvents(bootstrapServers, Set.of(sourceEventIds));
    }

    private long unpublished() {
        return Db.count(dataSource, "select count(*) from ingest.outbox where published_at is null");
    }

    /** What a freshly started gateway would have: new workers, a new producer, nothing in memory. */
    private Restarted restart() {
        var publisher = new KafkaEventPublisher(bootstrapServers, castor, topics);
        return new Restarted(
                publisher,
                new NormalizeInbox(normalizationStore, adapters, failPoints, meters, castor),
                new RelayOutbox(outbox, publisher, failPoints, meters, castor));
    }

    private record Restarted(KafkaEventPublisher publisher, NormalizeInbox normalizer, RelayOutbox relay)
            implements AutoCloseable {
        @Override
        public void close() {
            publisher.close();
        }
    }

    @Test
    void anEventIsPublishedOnceWithItsAggregateAsKeyAndMarkedPublishedAfterTheAck() {
        String sourceEventId = Events.newSourceEventId();
        String receptionId = accept(sourceEventId);
        normalizer.runOnce();

        assertEquals(1, relay.runOnce());

        List<Message> messages = onKafka(sourceEventId);
        assertEquals(1, messages.size());
        Message message = messages.get(0);
        assertEquals("crane-mhc-2", message.key());
        assertEquals(EventIds.of(UUID.fromString(receptionId), 0).toString(), message.eventId());
        assertEquals("equipment.status.changed", message.type());
        assertEquals(message.eventId(), message.event().eventId());
        assertEquals(receptionId, message.event().receptionId());
        assertEquals("westports", message.event().portId());
        assertEquals(clock.instant(), message.event().receivedAt());
        assertEquals("BLOCKED", message.event().payload().get("status").asText());
        assertEquals(
                Timestamp.from(clock.instant()),
                Db.value(dataSource, "select published_at from ingest.outbox"),
                "published_at is the clock at the time of the ack");

        assertEquals(0, relay.runOnce(), "nothing left to send");
        assertEquals(1, onKafka(sourceEventId).size());
    }

    @Test
    void theSameSourceEventIdTwiceGivesOneInboxRowAndOneKafkaMessage() {
        String sourceEventId = Events.newSourceEventId();
        String first = post(Events.status(sourceEventId))
                .then()
                .statusCode(202)
                .extract()
                .path("receptionId");
        post(Events.status(sourceEventId)).then().statusCode(200).extract().path("receptionId");

        normalizer.runOnce();
        relay.runOnce();
        normalizer.runOnce();
        relay.runOnce();

        assertEquals(
                1, Db.count(dataSource, "select count(*) from ingest.inbox where source_event_id = ?", sourceEventId));
        assertEquals(1, onKafka(sourceEventId).size());
        assertEquals(first, onKafka(sourceEventId).get(0).event().receptionId());
    }

    @Test
    void eventsOfOneAggregateReachKafkaInTheOrderTheyWereSent() {
        String run = UUID.randomUUID().toString();
        List<String> ids =
                IntStream.range(0, 60).mapToObj(i -> "ord-" + i + "-" + run).toList();
        // Three aggregates interleaved, all in one request so the inbox order is the array order
        String body = IntStream.range(0, 60)
                .mapToObj(i -> Events.status(ids.get(i), "aggregate-" + (i % 3) + "-" + run, "2026-10-29T08:20:00Z"))
                .collect(Collectors.joining(",", "[", "]"));
        post(body).then().statusCode(207);

        while (normalizer.runOnce() > 0) {
            // drain
        }
        while (relay.runOnce() > 0) {
            // drain
        }

        Map<String, List<Message>> byKey = onKafka(ids.toArray(String[]::new)).stream()
                .collect(Collectors.groupingBy(Message::key, LinkedHashMap::new, Collectors.toList()));
        assertEquals(3, byKey.size());
        for (var entry : byKey.entrySet()) {
            int aggregate = Integer.parseInt(entry.getKey().split("-")[1]);
            List<String> expected = IntStream.range(0, 60)
                    .filter(i -> i % 3 == aggregate)
                    .mapToObj(ids::get)
                    .toList();
            // One partition per key, and within it the offsets give the order
            assertEquals(
                    1,
                    entry.getValue().stream().map(Message::partition).distinct().count(),
                    entry.getKey());
            assertEquals(
                    expected,
                    entry.getValue().stream()
                            .sorted(java.util.Comparator.comparingLong(Message::offset))
                            .map(m -> m.event().sourceEventId())
                            .toList(),
                    "order of " + entry.getKey());
        }
    }

    @Test
    void gatewayStoppedBeforeTheRelayThenRestartedPublishesTheEventOnce() {
        // PRD 19.2
        String sourceEventId = Events.newSourceEventId();
        failPoints.arm(FailPoint.BEFORE_RELAY);
        String receptionId = accept(sourceEventId);
        assertEquals(1, normalizer.runOnce());

        assertThrows(FailPoints.FailPointException.class, relay::runOnce);

        assertTrue(onKafka(sourceEventId).isEmpty(), "the gateway stopped before sending anything");
        assertEquals("NORMALIZED", inboxStatus(receptionId));
        assertEquals(1, unpublished(), "the event is waiting in the outbox");

        failPoints.disarm();
        try (Restarted gateway = restart()) {
            assertEquals(0, gateway.normalizer().runOnce());
            assertEquals(1, gateway.relay().runOnce());
            assertEquals(0, gateway.relay().runOnce());
        }

        List<Message> messages = onKafka(sourceEventId);
        assertEquals(1, messages.size(), "on Kafka once");
        assertEquals(
                EventIds.of(UUID.fromString(receptionId), 0).toString(),
                messages.get(0).eventId());
        assertEquals(0, unpublished());
    }

    @Test
    void gatewayStoppedBeforeNormalizingThenRestartedStillPublishesTheEventOnce() {
        String sourceEventId = Events.newSourceEventId();
        failPoints.arm(FailPoint.BEFORE_NORMALIZE);
        String receptionId = accept(sourceEventId);

        assertThrows(FailPoints.FailPointException.class, normalizer::runOnce);
        assertEquals("RECEIVED", inboxStatus(receptionId));
        assertEquals(0, Db.count(dataSource, "select count(*) from ingest.outbox"));

        failPoints.disarm();
        try (Restarted gateway = restart()) {
            assertEquals(1, gateway.normalizer().runOnce());
            assertEquals(1, gateway.relay().runOnce());
        }

        assertEquals(1, onKafka(sourceEventId).size());
    }

    @Test
    void gatewayStoppedBetweenTheAckAndTheMarkSendsTheSameEventAgainWithTheSameEventId() {
        String sourceEventId = Events.newSourceEventId();
        String receptionId = accept(sourceEventId);
        normalizer.runOnce();
        failPoints.arm(FailPoint.AFTER_SEND_BEFORE_MARK);

        assertThrows(FailPoints.FailPointException.class, relay::runOnce);

        assertEquals(1, onKafka(sourceEventId).size(), "the broker has it");
        assertEquals(1, unpublished(), "but the gateway never marked it");
        assertNull(Db.value(dataSource, "select published_at from ingest.outbox"));

        failPoints.disarm();
        try (Restarted gateway = restart()) {
            assertEquals(1, gateway.relay().runOnce());
        }

        // At-least-once: a second copy with the same eventId, which STATE-03 drops by that id
        List<Message> messages = onKafka(sourceEventId);
        assertEquals(2, messages.size());
        assertEquals(1, messages.stream().map(Message::eventId).distinct().count());
        assertEquals(
                EventIds.of(UUID.fromString(receptionId), 0).toString(),
                messages.get(0).eventId());
        assertEquals(messages.get(0).event(), messages.get(1).event());
        assertEquals(0, unpublished());
        assertEquals(0, relay.runOnce());
    }

    @Test
    void onlyOneRelayRunsAtATimeEvenAcrossProcesses() throws Exception {
        String sourceEventId = Events.newSourceEventId();
        accept(sourceEventId);
        normalizer.runOnce();

        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (var lock = other.prepareStatement("select pg_advisory_xact_lock(?)")) {
                lock.setLong(1, JdbcOutboxStore.RELAY_LOCK);
                lock.execute();

                assertEquals(0, relay.runOnce(), "another relay holds the lock");
                assertTrue(onKafka(sourceEventId).isEmpty());
            } finally {
                other.rollback();
            }
        }

        assertEquals(1, relay.runOnce());
        assertEquals(1, onKafka(sourceEventId).size());
    }

    @Test
    void aBatchOfEventsIsPublishedByRunsOfAtMostTheBatchSize() {
        String run = UUID.randomUUID().toString();
        String body = IntStream.range(0, 120)
                .mapToObj(i -> Events.status("many-" + i + "-" + run, "bulk-" + (i % 7), "2026-10-29T08:20:00Z")
                        .replaceAll("\\s+", ""))
                .collect(Collectors.joining(",", "[", "]"));
        post(body).then().statusCode(207);
        while (normalizer.runOnce() > 0) {
            // drain
        }

        int published = 0;
        for (int n = relay.runOnce(); n > 0; n = relay.runOnce()) {
            assertTrue(n <= castor.relay().batchSize());
            published += n;
        }

        assertEquals(120, published);
        Set<String> seen = new HashSet<>();
        KafkaProbe.all(bootstrapServers).stream()
                .filter(m -> m.event().sourceEventId().endsWith(run))
                .forEach(m -> assertTrue(seen.add(m.eventId()), "duplicate " + m.eventId()));
        assertEquals(120, seen.size());
    }
}

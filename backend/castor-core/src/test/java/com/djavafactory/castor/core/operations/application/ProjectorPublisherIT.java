package com.djavafactory.castor.core.operations.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static com.djavafactory.castor.core.support.ProjectionTestSupport.count;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The state changes of the projections reach castor.state.changed.v1 in order, once, with the DTO as their data. */
@QuarkusTest
class ProjectorPublisherIT {

    private static final Instant T0 = Instant.parse("2026-10-30T08:00:00Z");
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    @Inject
    DataSource dataSource;

    @Inject
    CanonicalEventProcessor processor;

    @Inject
    StateChangePublisher publisher;

    @Inject
    ObjectMapper json;

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String bootstrapServers;

    private Layout layout;
    private SteppedClock clock;
    private TestEvents events;
    private long offset;

    @BeforeEach
    void setUp() {
        layout = ProjectionTestSupport.publishedLayout(dataSource, 1);
        clock = new SteppedClock(T0);
        events = new TestEvents(clock);
        offset = 0;
    }

    @AfterEach
    void tearDown() {
        ProjectionTestSupport.clearState(dataSource);
    }

    private void process(CanonicalEvent event) {
        processor.process(
                new IncomingRecord(Topics.INGEST_CANONICAL, 0, offset++, event.aggregateId(), TestEvents.bytes(event)));
    }

    @Test
    void unpublishedChangesAreSentInOrderKeyedByPortAndMarkedPublished() throws Exception {
        process(events.status(layout.crane(), "WORKING", null));
        clock.step(1);
        process(events.position(layout.crane(), ProjectionTestSupport.LON, ProjectionTestSupport.LAT, 10.0, 2));
        clock.step(1);
        process(events.vesselCall("MV-AURORA-0412", "ALONGSIDE", layout.berth()));
        assertEquals(3, count(dataSource, "select count(*) from ops.state_change where published_at is null"));

        assertEquals(3, publisher.publishPending());

        assertEquals(0, count(dataSource, "select count(*) from ops.state_change where published_at is null"));
        // the topic is shared by the tests of this JVM: keep the messages of this test's crane and call
        var sent = new KafkaTestClient(bootstrapServers)
                        .readUntil(
                                Topics.STATE_CHANGED,
                                found -> found.stream().filter(this::mine).count() >= 3,
                                Duration.ofSeconds(30))
                        .stream()
                        .filter(this::mine)
                        .toList();
        assertEquals(3, sent.size());
        List<Map<String, Object>> messages = sent.stream().map(this::read).toList();
        assertEquals(
                List.of(1, 2, 3), messages.stream().map(m -> m.get("sequence")).toList());
        assertEquals(
                List.of("equipment", "equipment", "vessel-call"),
                messages.stream().map(m -> m.get("entityType")).toList());
        assertTrue(sent.stream().allMatch(r -> r.key().equals("westports")));
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) messages.get(0).get("data");
        assertEquals("WORKING", data.get("status"));
        assertEquals(layout.crane().toString(), messages.get(0).get("entityId"));
        assertEquals("westports", messages.get(0).get("portId"));
    }

    @Test
    void nothingIsSentTwiceAndLaterChangesFollowOn() {
        process(events.status(layout.crane(), "WORKING", null));
        assertEquals(1, publisher.publishPending());
        assertEquals(0, publisher.publishPending());

        clock.step(5);
        process(events.status(layout.crane(), "DOWN", null));

        assertEquals(1, publisher.publishPending());
        assertEquals(0, count(dataSource, "select count(*) from ops.state_change where published_at is null"));
        assertEquals(2, count(dataSource, "select count(*) from ops.state_change where published_at is not null"));
    }

    private Map<String, Object> read(org.apache.kafka.clients.consumer.ConsumerRecord<String, byte[]> record) {
        try {
            return json.readValue(record.value(), OBJECT);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean mine(org.apache.kafka.clients.consumer.ConsumerRecord<String, byte[]> record) {
        String text = new String(record.value(), java.nio.charset.StandardCharsets.UTF_8);
        return text.contains(layout.crane().toString()) || text.contains("MV-AURORA-0412");
    }
}

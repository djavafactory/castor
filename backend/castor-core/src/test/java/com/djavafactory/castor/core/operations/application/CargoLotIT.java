package com.djavafactory.castor.core.operations.application;

import java.time.Duration;
import java.util.List;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.contracts.CanonicalEvent;
import com.djavafactory.castor.contracts.Topics;
import com.djavafactory.castor.core.support.KafkaTestClient;
import com.djavafactory.castor.core.support.ProjectorHarness;

import static com.djavafactory.castor.core.support.ProjectionTestSupport.count;
import static com.djavafactory.castor.core.support.ProjectionTestSupport.lines;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Cargo lots and movements against the database: exactly once, deltas, and a lot that never moves backwards. */
@QuarkusTest
class CargoLotIT {

    private static final String LOT = "itap:COIL-7";

    @Inject
    DataSource dataSource;

    @Inject
    CanonicalEventProcessor processor;

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String bootstrapServers;

    private ProjectorHarness h;

    @BeforeEach
    void setUp() {
        h = new ProjectorHarness(dataSource, processor);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private String yardBalance() {
        return lines(
                        dataSource,
                        "select coalesce(quantity::text, 'unknown') from ops.storage_inventory where storage_asset_id = '"
                                + h.layout.yard() + "' and commodity = 'COIL'")
                .stream()
                .findFirst()
                .orElse("none");
    }

    @Test
    void theSameCanonicalEventSentTwiceToKafkaAddsFiveCoilsOnce() {
        CanonicalEvent registered = h.events.lotRegistered(LOT, "BREAKBULK", "COIL", "PIECES", 60, null);
        h.clock.step(60);
        CanonicalEvent moved = h.events.lotMoved(LOT, h.layout.berth(), h.layout.yard(), 5, "PIECES");
        h.clock.step(60);
        // behind the duplicate, on the same key and so the same partition: when this one is applied the duplicate was
        // seen
        CanonicalEvent marker = h.events.lotMoved(LOT, h.layout.berth(), h.layout.yard(), 1, "PIECES");

        new KafkaTestClient(bootstrapServers)
                .publish(Topics.INGEST_CANONICAL, List.of(registered, moved, moved, marker));

        await().atMost(Duration.ofSeconds(60))
                .until(() -> count(
                                dataSource,
                                "select count(*) from ops.processed_event where event_id = '" + marker.eventId() + "'")
                        == 1);
        assertEquals("6.000", yardBalance(), "five coils once, and the one of the marker");
        assertEquals(2, count(dataSource, "select count(*) from ops.cargo_movement"));
        assertEquals(3, count(dataSource, "select count(*) from ops.canonical_event"));
        assertEquals(0, count(dataSource, "select count(*) from ops.dead_letter"));
    }

    @Test
    void thirtyFiveCoilsInAndTwentyFiveOutOfABufferOverTwoHoursNetTen() {
        h.process(h.events.lotRegistered(LOT, "BREAKBULK", "COIL", "PIECES", 60, null));
        for (int i = 0; i < 7; i++) {
            h.clock.step(600);
            h.process(h.events.lotMoved(LOT, h.layout.berth(), h.layout.yard(), 5, "PIECES"));
        }
        for (int i = 0; i < 5; i++) {
            h.clock.step(600);
            h.process(h.events.lotMoved(LOT, h.layout.yard(), h.layout.berth(), 5, "PIECES"));
        }

        assertEquals("10.000", yardBalance());
        assertEquals(12, count(dataSource, "select count(*) from ops.cargo_movement"));
        assertEquals(0, count(dataSource, "select count(*) from ops.reconciliation_item"));
        assertEquals(0, count(dataSource, "select count(*) from ops.inventory_adjustment"));
    }

    @Test
    void anOlderMovementAfterANewerOneKeepsBalancesRightDoesNotMoveTheLotBackAndOpensOneStaleItem() {
        h.process(h.events.lotRegistered(LOT, "BREAKBULK", "COIL", "PIECES", 60, null));
        h.clock.step(3600);
        h.process(h.events.lotMoved(LOT, h.layout.berth(), h.layout.yard(), 10, "PIECES"));
        CanonicalEvent older = h.events.at(
                h.events.lotMoved(LOT, h.layout.berth(), h.layout.shed(), 4, "PIECES"),
                ProjectorHarness.T0.plusSeconds(60));
        h.process(older);

        assertEquals("10.000", yardBalance());
        assertEquals(
                List.of("4.000"),
                lines(
                        dataSource,
                        "select quantity::text from ops.storage_inventory where storage_asset_id = '" + h.layout.shed()
                                + "'"));
        assertEquals(
                List.of(h.layout.yard().toString()),
                lines(dataSource, "select current_location_id::text from ops.cargo_lot"));
        assertEquals(
                List.of("STALE_EVENT " + older.eventId()),
                lines(dataSource, "select kind || ' ' || event_id from ops.reconciliation_item"));
        assertEquals(2, count(dataSource, "select count(*) from ops.cargo_movement"));
    }

    @Test
    void aMovementInAnotherUnitThanItsLotIsDeadLetteredAsAUnitMismatchAndNothingMoves() {
        h.process(h.events.lotRegistered(LOT, "BREAKBULK", "COIL", "PIECES", 60, null));
        h.clock.step(60);
        h.process(h.events.lotMoved(LOT, h.layout.berth(), h.layout.yard(), 5, "TONNES"));

        assertEquals(
                List.of("UNIT_MISMATCH"),
                lines(dataSource, "select substring(error from '^[A-Z_]+') from ops.dead_letter"));
        assertEquals(0, count(dataSource, "select count(*) from ops.cargo_movement"));
        assertEquals(0, count(dataSource, "select count(*) from ops.storage_inventory"));
    }

    @Test
    void aFamilyThatIsNotInTheReferenceDataIsDeadLettered() {
        h.process(h.events.lotRegistered(LOT, "NO_SUCH_FAMILY", "COIL", "PIECES", 60, null));

        assertEquals(0, count(dataSource, "select count(*) from ops.cargo_lot"));
        assertEquals(
                List.of("UNKNOWN_ENTITY"),
                lines(dataSource, "select substring(error from '^[A-Z_]+') from ops.dead_letter"));
    }

    @Test
    void everyAppliedMovementIsAStateChangeOfTheLotAndOfTheBalance() {
        h.process(h.events.lotRegistered(LOT, "BREAKBULK", "COIL", "PIECES", 60, null));
        h.clock.step(60);
        h.process(h.events.lotMoved(LOT, h.layout.berth(), h.layout.yard(), 5, "PIECES"));

        assertEquals(
                List.of("cargo-lot", "inventory", "cargo-lot"),
                lines(dataSource, "select entity_type from ops.state_change order by sequence"));
    }
}

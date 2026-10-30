package com.djavafactory.castor.core.operations.infrastructure;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.contracts.Quality;
import com.djavafactory.castor.contracts.Quantity;
import com.djavafactory.castor.contracts.Unit;
import com.djavafactory.castor.core.operations.domain.CanonicalEventRecord;
import com.djavafactory.castor.core.operations.domain.CargoFamilyRepository;
import com.djavafactory.castor.core.operations.domain.CargoLot;
import com.djavafactory.castor.core.operations.domain.CargoMovement;
import com.djavafactory.castor.core.operations.domain.CargoRepository;
import com.djavafactory.castor.core.operations.domain.DeadLetter;
import com.djavafactory.castor.core.operations.domain.DeadLetterRepository;
import com.djavafactory.castor.core.operations.domain.EquipmentRepository;
import com.djavafactory.castor.core.operations.domain.EquipmentState;
import com.djavafactory.castor.core.operations.domain.EquipmentStatus;
import com.djavafactory.castor.core.operations.domain.EquipmentStatusInterval;
import com.djavafactory.castor.core.operations.domain.EventLogRepository;
import com.djavafactory.castor.core.operations.domain.GateVisit;
import com.djavafactory.castor.core.operations.domain.GateVisitRepository;
import com.djavafactory.castor.core.operations.domain.InventoryAdjustment;
import com.djavafactory.castor.core.operations.domain.InventoryRepository;
import com.djavafactory.castor.core.operations.domain.MassBasis;
import com.djavafactory.castor.core.operations.domain.NegativeBalanceException;
import com.djavafactory.castor.core.operations.domain.Position;
import com.djavafactory.castor.core.operations.domain.Provenance;
import com.djavafactory.castor.core.operations.domain.ReconciliationItem;
import com.djavafactory.castor.core.operations.domain.ReconciliationKind;
import com.djavafactory.castor.core.operations.domain.ReconciliationRepository;
import com.djavafactory.castor.core.operations.domain.ReconciliationStatus;
import com.djavafactory.castor.core.operations.domain.StateChange;
import com.djavafactory.castor.core.operations.domain.StateChangeRepository;
import com.djavafactory.castor.core.operations.domain.StorageInventory;
import com.djavafactory.castor.core.operations.domain.VesselCall;
import com.djavafactory.castor.core.operations.domain.VesselCallRepository;
import com.djavafactory.castor.core.operations.domain.VesselStatus;
import com.djavafactory.castor.core.shared.domain.ClockInfo;
import com.djavafactory.castor.core.shared.domain.ConflictException;
import com.djavafactory.castor.core.support.LayoutTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every ops repository against a real PostGIS: round trips, optimistic versions, replays and the clock beans. */
@QuarkusTest
class OpsRepositoriesIT {

    private static final Instant T0 = Instant.parse("2026-10-30T08:00:00Z");

    @Inject
    DataSource dataSource;

    @Inject
    CargoFamilyRepository families;

    @Inject
    VesselCallRepository vessels;

    @Inject
    EquipmentRepository equipment;

    @Inject
    CargoRepository cargo;

    @Inject
    InventoryRepository inventories;

    @Inject
    GateVisitRepository gates;

    @Inject
    EventLogRepository events;

    @Inject
    StateChangeRepository changes;

    @Inject
    ReconciliationRepository reconciliation;

    @Inject
    DeadLetterRepository deadLetters;

    @Inject
    InstantSource clock;

    @Inject
    ClockInfo clockInfo;

    private long port;
    private UUID crane;
    private UUID pad;
    private UUID berth;

    @BeforeEach
    void setUp() throws Exception {
        LayoutTestSupport.clear(dataSource);
        try (var c = dataSource.getConnection()) {
            port = LayoutTestSupport.portId(c);
            crane = LayoutTestSupport.asset(c, "STS1", "STS_CRANE");
            pad = LayoutTestSupport.asset(c, "PAD1", "STORAGE_PAD");
            berth = LayoutTestSupport.asset(c, "B1", "BERTH");
        }
    }

    @AfterEach
    void tearDown() {
        LayoutTestSupport.clear(dataSource);
    }

    private <T> T tx(java.util.concurrent.Callable<T> work) {
        return QuarkusTransaction.requiringNew().call(work);
    }

    private static Provenance prov(Instant at) {
        return new Provenance("CBAS", Quality.OBSERVED, at);
    }

    @Test
    void cargoFamiliesAreLoadedFromRef() {
        var all = families.findAll();
        assertTrue(all.stream().anyMatch(f -> f.code().equals("LIQUID_BULK") && f.defaultUnit() == Unit.CUBIC_METRES));
        assertTrue(families.find("RORO").isPresent());
        assertTrue(families.find("FRUIT").isEmpty());
    }

    @Test
    void clockIsTheSystemClockAtSpeedOne() {
        assertEquals(1, clockInfo.speed());
        assertTrue(Math.abs(clock.instant().toEpochMilli() - System.currentTimeMillis()) < 5_000);
    }

    @Test
    void vesselCallKeepsUnknownFieldsNullAndChecksTheVersion() {
        var call = new VesselCall(
                null,
                port,
                "CALL-1",
                "MV Aurora",
                null,
                null,
                null,
                VesselStatus.EXPECTED,
                T0.plusSeconds(3600),
                null,
                null,
                null,
                null,
                prov(T0),
                T0,
                0);
        VesselCall saved = tx(() -> vessels.insert(call));
        assertNotNull(saved.id());

        VesselCall read = vessels.find(port, "CALL-1").orElseThrow();
        assertNull(read.imo());
        assertNull(read.loaM());
        assertNull(read.ata());
        assertEquals(T0.plusSeconds(3600), read.eta());
        assertEquals(Quality.OBSERVED, read.provenance().quality());

        var arrived = new VesselCall(
                read.id(),
                port,
                "CALL-1",
                "MV Aurora",
                "9074729",
                new BigDecimal("189.9"),
                new BigDecimal("32.2"),
                VesselStatus.ALONGSIDE,
                read.eta(),
                T0.plusSeconds(60),
                null,
                null,
                berth,
                prov(T0.plusSeconds(60)),
                T0.plusSeconds(60),
                read.version());
        VesselCall updated = tx(() -> vessels.update(arrived));
        assertEquals(1, updated.version());
        assertEquals(berth, vessels.find(port, "CALL-1").orElseThrow().berthAssetId());
        assertThrows(ConflictException.class, () -> tx(() -> vessels.update(arrived)));
        assertEquals(1, vessels.findAll(port).size());
    }

    @Test
    void equipmentStateStoresPositionAndStatusHistory() {
        var position = new Position(101.3066, 2.9534, new BigDecimal("90.5"), new BigDecimal("2.5"), T0);
        var state = new EquipmentState(
                port, crane, EquipmentStatus.WORKING, null, position, Map.of("jobId", "J1"), prov(T0), T0, 7L, 0);
        tx(() -> equipment.insert(state));

        EquipmentState read = equipment.find(port, crane).orElseThrow();
        assertEquals(101.3066, read.position().lon(), 1e-9);
        assertEquals(2.9534, read.position().lat(), 1e-9);
        assertEquals(0, new BigDecimal("90.5").compareTo(read.position().headingDeg()));
        assertEquals(Map.of("jobId", "J1"), read.assignment());
        assertEquals(7L, read.lastSourceSequence());

        var unknownPosition = new EquipmentState(
                port,
                crane,
                EquipmentStatus.IDLE,
                "no job",
                null,
                null,
                prov(T0.plusSeconds(10)),
                T0.plusSeconds(10),
                null,
                read.version());
        tx(() -> equipment.update(unknownPosition));
        EquipmentState after = equipment.find(port, crane).orElseThrow();
        assertNull(after.position(), "an unknown position is null, never 0,0");
        assertNull(after.assignment());
        assertEquals(1, after.version());
        assertThrows(ConflictException.class, () -> tx(() -> equipment.update(unknownPosition)));

        tx(() -> {
            equipment.startInterval(new EquipmentStatusInterval(
                    port, crane, EquipmentStatus.WORKING, null, T0, null, "CBAS", Quality.OBSERVED));
            return null;
        });
        assertEquals(
                EquipmentStatus.WORKING,
                equipment.openInterval(port, crane).orElseThrow().status());
        assertTrue(tx(() -> equipment.closeInterval(port, crane, T0.plusSeconds(10))));
        assertFalse(tx(() -> equipment.closeInterval(port, crane, T0.plusSeconds(20))));
        assertTrue(equipment.openInterval(port, crane).isEmpty());
        assertEquals(
                T0.plusSeconds(10), equipment.intervals(port, crane, 10).get(0).endedAt());
        assertEquals(1, equipment.findAll(port).size());
    }

    @Test
    void cargoLotsAndImmutableMovementsAreReplaySafe() {
        var lot = new CargoLot(
                null,
                port,
                "LOT-1",
                null,
                "DRY_BULK",
                "Thermal coal",
                Unit.TONNES,
                null,
                null,
                null,
                null,
                "SCSS",
                Quality.OBSERVED,
                0);
        CargoLot saved = tx(() -> cargo.insertLot(lot));
        CargoLot read = cargo.findLot(port, "LOT-1").orElseThrow();
        assertNull(read.registeredQuantity(), "an unknown quantity is null, not 0");
        assertNull(read.massBasis());

        var located = new CargoLot(
                saved.id(),
                port,
                "LOT-1",
                "CONS-9",
                "DRY_BULK",
                "Thermal coal",
                Unit.TONNES,
                new BigDecimal("1500.5"),
                MassBasis.WET,
                pad,
                T0,
                "SCSS",
                Quality.OBSERVED,
                saved.version());
        tx(() -> cargo.updateLot(located));
        assertEquals(pad, cargo.findLot(saved.id()).orElseThrow().currentLocationId());
        assertThrows(ConflictException.class, () -> tx(() -> cargo.updateLot(located)));

        UUID event = UUID.randomUUID();
        var move = new CargoMovement(
                port, saved.id(), berth, pad, new Quantity(new BigDecimal("500"), Unit.TONNES), crane, T0, event);
        assertTrue(tx(() -> cargo.appendMovement(move)));
        assertFalse(tx(() -> cargo.appendMovement(move)), "the same event twice stores one movement");
        assertEquals(1, cargo.movements(saved.id()).size());
        assertEquals(crane, cargo.movements(saved.id()).get(0).handledBy());
    }

    @Test
    void inventoryAdjustmentsKeepTheBalanceAndRefuseANegativeOne() {
        var start = new StorageInventory(
                port, pad, "COAL", new Quantity(new BigDecimal("100"), Unit.TONNES), T0, "SCSS", Quality.OBSERVED, 0);
        tx(() -> inventories.insert(start));

        UUID event = UUID.randomUUID();
        var delta = new Quantity(new BigDecimal("-30"), Unit.TONNES);
        tx(() -> {
            StorageInventory current =
                    inventories.findForUpdate(port, pad, "COAL").orElseThrow();
            assertTrue(inventories.appendAdjustment(
                    new InventoryAdjustment(port, pad, "COAL", delta, "loaded", event, T0)));
            inventories.update(current.adjustedBy(delta, T0, "EDIC", Quality.OBSERVED));
            return null;
        });
        StorageInventory read = inventories.find(port, pad, "COAL").orElseThrow();
        assertEquals(0, new BigDecimal("70").compareTo(read.quantity().value()));
        assertEquals(1, read.version());
        assertFalse(tx(() ->
                inventories.appendAdjustment(new InventoryAdjustment(port, pad, "COAL", delta, "loaded", event, T0))));
        assertEquals(1, inventories.adjustments(port, pad).size());
        assertEquals(1, inventories.findByStorage(port, pad).size());
        assertThrows(
                NegativeBalanceException.class,
                () -> read.adjustedBy(new Quantity(new BigDecimal("-71"), Unit.TONNES), T0, "EDIC", Quality.OBSERVED));
    }

    @Test
    void gateVisitIsCompletedByASecondTransaction() {
        tx(() -> {
            gates.save(new GateVisit(port, "TV-1", pad, T0, null, "L2", "EDIC", Quality.OBSERVED));
            return null;
        });
        assertNull(gates.find(port, "TV-1", pad).orElseThrow().outAt());
        tx(() -> {
            gates.save(new GateVisit(port, "TV-1", pad, T0, T0.plusSeconds(600), "L2", "EDIC", Quality.OBSERVED));
            return null;
        });
        assertEquals(
                T0.plusSeconds(600), gates.find(port, "TV-1", pad).orElseThrow().outAt());
    }

    @Test
    void canonicalEventsAreStoredOnceAndProcessedOncePerConsumer() {
        UUID id = UUID.randomUUID();
        var event = new CanonicalEventRecord(
                id,
                port,
                "gate.transaction",
                "g1",
                T0,
                T0.plusSeconds(1),
                Quality.SIMULATED,
                Map.of("direction", "IN"));
        assertTrue(tx(() -> events.append(event)));
        assertFalse(tx(() -> events.append(event)));
        assertEquals(Map.of("direction", "IN"), events.find(id).orElseThrow().body());
        assertEquals(Quality.SIMULATED, events.find(id).orElseThrow().quality());

        assertFalse(events.isProcessed("state-projector", id));
        assertTrue(tx(() -> events.markProcessed(port, "state-projector", id)));
        assertFalse(tx(() -> events.markProcessed(port, "state-projector", id)));
        assertTrue(tx(() -> events.markProcessed(port, "other", id)));
        assertTrue(events.isProcessed("state-projector", id));
    }

    @Test
    void stateChangesAreOrderedAndPublishedInOrder() {
        for (int i = 1; i <= 3; i++) {
            int n = i;
            StateChange c = tx(() -> changes.append(port, "equipment", "STS1", n, T0, Map.of("n", n)));
            assertEquals(n, c.sequence());
        }
        assertEquals(3, changes.lastSequence(port));
        assertEquals(
                List.of(2L, 3L),
                changes.after(port, 1, 10).stream().map(StateChange::sequence).toList());
        tx(() -> {
            changes.markPublished(port, 2, T0);
            return null;
        });
        assertEquals(
                List.of(3L),
                changes.unpublished(port, 10).stream()
                        .map(StateChange::sequence)
                        .toList());
        assertEquals(0, changes.lastSequence(port + 1000));
    }

    @Test
    void reconciliationItemsAreOpenedAndResolvedOnce() {
        var item = new ReconciliationItem(
                null,
                port,
                ReconciliationKind.NEGATIVE_BALANCE,
                "inventory",
                "PAD1",
                UUID.randomUUID(),
                Map.of("delta", -71),
                ReconciliationStatus.OPEN,
                null,
                null);
        ReconciliationItem opened = tx(() -> reconciliation.open(item));
        assertNotNull(opened.id());
        assertEquals(
                1,
                reconciliation.findByStatus(port, ReconciliationStatus.OPEN, 10).size());
        assertTrue(tx(() -> reconciliation.resolve(opened.id(), "operator", "recounted", T0)));
        assertFalse(tx(() -> reconciliation.resolve(opened.id(), "operator", "again", T0)));
        ReconciliationItem resolved = reconciliation.find(opened.id()).orElseThrow();
        assertEquals(ReconciliationStatus.RESOLVED, resolved.status());
        assertEquals("recounted", resolved.note());
        assertTrue(
                reconciliation.findByStatus(port, ReconciliationStatus.OPEN, 10).isEmpty());
    }

    @Test
    void deadLettersKeepTheirPlaceInKafkaAndLinkReplays() {
        var letter = new DeadLetter(
                null,
                port,
                "state-projector",
                UUID.randomUUID(),
                "castor.events",
                2,
                41L,
                "boom",
                3,
                Map.of("raw", "x"),
                null);
        DeadLetter first = tx(() -> deadLetters.add(letter));
        var replay = new DeadLetter(
                null,
                port,
                "state-projector",
                letter.eventId(),
                "castor.events",
                2,
                41L,
                "boom again",
                1,
                null,
                first.id());
        DeadLetter second = tx(() -> deadLetters.add(replay));
        assertEquals(first.id(), deadLetters.find(second.id()).orElseThrow().replayOf());
        assertEquals(41L, deadLetters.find(first.id()).orElseThrow().offset());
        assertEquals(2, deadLetters.findByConsumer(port, "state-projector", 10).size());
    }
}

package com.djavafactory.castor.core.operations.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.contracts.Quality;
import com.djavafactory.castor.contracts.Quantity;
import com.djavafactory.castor.contracts.Unit;
import com.djavafactory.castor.core.operations.application.StateChangeRecorder;
import com.djavafactory.castor.core.operations.domain.CanonicalEventRecord;
import com.djavafactory.castor.core.operations.domain.EquipmentRepository;
import com.djavafactory.castor.core.operations.domain.EquipmentState;
import com.djavafactory.castor.core.operations.domain.EquipmentStatus;
import com.djavafactory.castor.core.operations.domain.EventLogRepository;
import com.djavafactory.castor.core.operations.domain.GateVisit;
import com.djavafactory.castor.core.operations.domain.GateVisitRepository;
import com.djavafactory.castor.core.operations.domain.InventoryRepository;
import com.djavafactory.castor.core.operations.domain.Position;
import com.djavafactory.castor.core.operations.domain.Provenance;
import com.djavafactory.castor.core.operations.domain.ReconciliationItem;
import com.djavafactory.castor.core.operations.domain.ReconciliationKind;
import com.djavafactory.castor.core.operations.domain.ReconciliationRepository;
import com.djavafactory.castor.core.operations.domain.ReconciliationStatus;
import com.djavafactory.castor.core.operations.domain.StorageInventory;
import com.djavafactory.castor.core.support.LayoutTestSupport;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The snapshot (STATE-02 part 1): what the STATE-01 repositories wrote, with provenance, one sequence, fast. */
@QuarkusTest
@TestSecurity(user = "viewer", roles = "VIEWER")
class SnapshotResourceIT {

    private static final Instant T0 = Instant.parse("2026-10-30T08:00:00Z");

    @Inject
    DataSource dataSource;

    @Inject
    EquipmentRepository equipment;

    @Inject
    InventoryRepository inventories;

    @Inject
    GateVisitRepository gates;

    @Inject
    ReconciliationRepository reconciliation;

    @Inject
    EventLogRepository events;

    @Inject
    StateChangeRecorder recorder;

    private long port;

    @BeforeEach
    void setUp() throws Exception {
        LayoutTestSupport.clear(dataSource);
        try (var c = dataSource.getConnection()) {
            port = LayoutTestSupport.portId(c);
        }
    }

    @AfterEach
    void tearDown() {
        LayoutTestSupport.clear(dataSource);
    }

    private UUID asset(String code, String type) throws Exception {
        try (var c = dataSource.getConnection()) {
            return LayoutTestSupport.asset(c, code, type);
        }
    }

    @Test
    void anEmptyPortHasSequenceZeroAndEmptyLists() {
        given().when()
                .get("/api/v1/ports/westports/state/snapshot")
                .then()
                .statusCode(200)
                .body("sequence", equalTo(0))
                .body("clockSpeed", equalTo(1.0f))
                .body("equipment", hasSize(0))
                .body("vesselCalls", hasSize(0))
                .body("inventories", hasSize(0))
                .body("openGateVisits", equalTo(0))
                .body("openReconciliationItems", equalTo(0));
    }

    @Test
    void stateWrittenThroughTheRepositoriesComesBackWithItsProvenance() throws Exception {
        UUID crane = asset("STS1", "STS_CRANE");
        UUID pad = asset("PAD1", "STORAGE_PAD");
        UUID gate = asset("G1", "GATE");
        QuarkusTransaction.requiringNew().run(() -> {
            equipment.insert(new EquipmentState(
                    port,
                    crane,
                    EquipmentStatus.WORKING,
                    null,
                    new Position(101.3066, 2.9534, new BigDecimal("90.5"), null, T0),
                    null,
                    new Provenance("CBAS", Quality.SIMULATED, T0),
                    T0,
                    null,
                    0));
            inventories.insert(new StorageInventory(
                    port,
                    pad,
                    "COAL",
                    new Quantity(new BigDecimal("1200.5"), Unit.TONNES),
                    T0,
                    "SCSS",
                    Quality.OBSERVED,
                    0));
            gates.save(new GateVisit(port, "TV-1", gate, T0, null, "L1", "SCSS", Quality.OBSERVED));
            gates.save(new GateVisit(port, "TV-2", gate, T0, T0.plusSeconds(60), "L1", "SCSS", Quality.OBSERVED));
            reconciliation.open(new ReconciliationItem(
                    null,
                    port,
                    ReconciliationKind.STALE_EVENT,
                    "equipment",
                    crane.toString(),
                    null,
                    Map.of(),
                    ReconciliationStatus.OPEN,
                    null,
                    null));
            recorder.record(port, "equipment", crane.toString(), 0, T0, Map.of("x", 1));
        });

        given().when()
                .get("/api/v1/ports/westports/state/snapshot")
                .then()
                .statusCode(200)
                .body("sequence", equalTo(1))
                .body("equipment", hasSize(1))
                .body("equipment[0].id", equalTo(crane.toString()))
                .body("equipment[0].status", equalTo("WORKING"))
                .body("equipment[0].quality", equalTo("SIMULATED"))
                .body("equipment[0].source", equalTo("CBAS"))
                .body("equipment[0].observedAt", equalTo("2026-10-30T08:00:00Z"))
                .body("equipment[0].freshnessSlaS", equalTo(120))
                .body("equipment[0].position.lon", equalTo(101.3066f))
                .body("equipment[0].assignment", nullValue())
                .body("inventories", hasSize(1))
                .body("inventories[0].id", equalTo(pad + ":COAL"))
                .body("inventories[0].quantity", equalTo(1200.5f))
                .body("inventories[0].unit", equalTo("TONNES"))
                .body("inventories[0].quality", equalTo("OBSERVED"))
                .body("inventories[0].source", equalTo("SCSS"))
                .body("inventories[0].observedAt", equalTo("2026-10-30T08:00:00Z"))
                .body("inventories[0].freshnessSlaS", equalTo(900))
                .body("openGateVisits", equalTo(1))
                .body("openReconciliationItems", equalTo(1));
    }

    @Test
    void anUnknownPortIsNotFound() {
        given().when().get("/api/v1/ports/nowhere/state/snapshot").then().statusCode(404);
    }

    @Test
    void assetStateHasTheLastTwentyEventsNewestFirst() throws Exception {
        UUID crane = asset("STS1", "STS_CRANE");
        QuarkusTransaction.requiringNew().run(() -> {
            equipment.insert(new EquipmentState(
                    port,
                    crane,
                    EquipmentStatus.IDLE,
                    "no job",
                    null,
                    null,
                    new Provenance("CBAS", Quality.OBSERVED, T0),
                    T0,
                    null,
                    0));
            for (int i = 0; i < 25; i++) {
                events.append(new CanonicalEventRecord(
                        UUID.randomUUID(),
                        port,
                        "equipment.status.changed",
                        crane.toString(),
                        T0.plusSeconds(i),
                        T0.plusSeconds(i),
                        Quality.OBSERVED,
                        Map.of("sourceSystem", "cbas")));
            }
        });

        given().when()
                .get("/api/v1/ports/westports/assets/" + crane + "/state")
                .then()
                .statusCode(200)
                .body("kind", equalTo("EQUIPMENT"))
                .body("equipment.status", equalTo("IDLE"))
                .body("lineage", hasSize(20))
                .body("lineage[0].occurredAt", equalTo("2026-10-30T08:00:24Z"))
                .body("lineage[0].type", equalTo("equipment.status.changed"))
                .body("lineage[0].source", equalTo("cbas"))
                .body("lineage[0].quality", equalTo("OBSERVED"));
        given().when()
                .get("/api/v1/ports/westports/assets/" + UUID.randomUUID() + "/state")
                .then()
                .statusCode(404);
    }

    @Test
    void snapshotOf500MachinesAnswersWithin300MsAtTheP95() throws Exception {
        List<UUID> ids = new ArrayList<>();
        try (var c = dataSource.getConnection()) {
            for (int i = 0; i < 500; i++) {
                ids.add(LayoutTestSupport.asset(c, "M" + i, "STS_CRANE"));
            }
        }
        QuarkusTransaction.requiringNew().run(() -> {
            for (UUID id : ids) {
                equipment.insert(new EquipmentState(
                        port,
                        id,
                        EquipmentStatus.WORKING,
                        null,
                        new Position(101.3066, 2.9534, null, null, T0),
                        Map.of("jobId", "J"),
                        new Provenance("CBAS", Quality.SIMULATED, T0),
                        T0,
                        null,
                        0));
            }
        });
        for (int i = 0; i < 5; i++) {
            given().when().get("/api/v1/ports/westports/state/snapshot").then().statusCode(200);
        }

        List<Long> millis = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            long start = System.nanoTime();
            given().when()
                    .get("/api/v1/ports/westports/state/snapshot")
                    .then()
                    .statusCode(200)
                    .body("equipment", hasSize(500));
            millis.add((System.nanoTime() - start) / 1_000_000);
        }
        Collections.sort(millis);
        long p95 = millis.get((int) Math.ceil(millis.size() * 0.95) - 1);
        assertTrue(p95 < 300, "snapshot p95 was " + p95 + " ms: " + millis);
    }
}

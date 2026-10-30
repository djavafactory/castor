package com.djavafactory.castor.core.operations.application;

import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.contracts.CanonicalEvent;
import com.djavafactory.castor.core.support.ProjectorHarness;

import static com.djavafactory.castor.core.support.ProjectionTestSupport.count;
import static com.djavafactory.castor.core.support.ProjectionTestSupport.lines;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Storage balances, the measured level next to them, the usable capacity, and the trail of what could not be applied. */
@QuarkusTest
@TestSecurity(user = "viewer", roles = "VIEWER")
class StorageIT {

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

    private JsonPath snapshot() {
        return given().get("/api/v1/ports/westports/state/snapshot")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    private void lot(String lot, String family, String commodity, String unit, double quantity) {
        h.process(h.events.lotRegistered(lot, family, commodity, unit, quantity, null));
        h.clock.step(10);
    }

    @Test
    void aTankLevelWithoutADensityHasAnUnknownMassInTheDto() {
        h.process(h.events.storageLevel(h.layout.tank(), "EDOIL", 3120.75, "CUBIC_METRES", null, 48.5));

        JsonPath json = snapshot();
        assertEquals("UNKNOWN", json.getString("inventories[0].observedMass.status"));
        assertNull(json.get("inventories[0].observedMass.tonnes"));
        assertEquals(3120.75f, json.getFloat("inventories[0].observed.quantity"));
        assertEquals("CUBIC_METRES", json.getString("inventories[0].observed.unit"));
        // no movement has been recorded: the book balance is unknown, never 0
        assertNull(json.get("inventories[0].quantity"));
        assertEquals("UNKNOWN", json.getString("inventories[0].bookMass.status"));
        assertEquals("KNOWN", json.getString("inventories[0].capacity.status"));
        assertEquals(1000f, json.getFloat("inventories[0].capacity.value"));
    }

    @Test
    void aTankLevelWithADensityHasAMassInTonnesAndAnAreaWithoutCapacityHasAnUnknownOne() {
        h.process(h.events.storageLevel(h.layout.tank(), "EDOIL", 1000, "CUBIC_METRES", 891.2, 48.5));
        h.clock.step(10);
        h.process(h.events.storageLevel(h.layout.yard(), "COIL", 12, "PIECES", null, null));

        JsonPath json = snapshot();
        List<String> assets = json.getList("inventories.storageAssetId");
        int tank = assets.indexOf(h.layout.tank().toString());
        int yard = assets.indexOf(h.layout.yard().toString());
        assertEquals("KNOWN", json.getString("inventories[" + tank + "].observedMass.status"));
        assertEquals(891.2f, json.getFloat("inventories[" + tank + "].observedMass.tonnes"));
        assertEquals(48.5f, json.getFloat("inventories[" + tank + "].observed.temperatureC"));
        assertEquals("UNKNOWN", json.getString("inventories[" + yard + "].capacity.status"));
        assertEquals("UNKNOWN", json.getString("inventories[" + yard + "].observedMass.status"));
    }

    @Test
    void aMeasuredLevelBeyondTwoPercentOfTheBookOpensASourceConflict() {
        lot("itap:OIL-1", "LIQUID_BULK", "OIL", "CUBIC_METRES", 1000);
        h.process(h.events.lotMoved("itap:OIL-1", h.layout.berth(), h.layout.tank(), 1000, "CUBIC_METRES"));
        h.clock.step(10);
        h.process(h.events.storageLevel(h.layout.tank(), "OIL", 1010, "CUBIC_METRES", null, null));
        assertEquals(0, count(dataSource, "select count(*) from ops.reconciliation_item"));

        h.clock.step(10);
        h.process(h.events.storageLevel(h.layout.tank(), "OIL", 1030, "CUBIC_METRES", null, null));

        assertEquals(
                List.of("SOURCE_CONFLICT inventory level 3.00"),
                lines(
                        dataSource,
                        "select kind || ' ' || entity_type || ' ' || (detail ->> 'field') || ' ' "
                                + "|| (detail ->> 'differencePercent') from ops.reconciliation_item"));
        assertEquals(
                "1000.000",
                lines(dataSource, "select quantity::text from ops.storage_inventory")
                        .get(0));
    }

    @Test
    void aMovementThatWouldGoNegativeIsRecordedTheBalanceStaysAndTheTrailIsComplete() {
        lot("itap:COIL-1", "BREAKBULK", "COIL", "PIECES", 60);
        h.process(h.events.lotMoved("itap:COIL-1", h.layout.berth(), h.layout.yard(), 10, "PIECES"));
        h.clock.step(10);
        CanonicalEvent out = h.events.lotMoved("itap:COIL-1", h.layout.yard(), h.layout.berth(), 15, "PIECES");
        h.process(out);

        assertEquals(
                List.of("10.000 true"),
                lines(dataSource, "select quantity || ' ' || needs_reconciliation from ops.storage_inventory"));
        assertEquals(2, count(dataSource, "select count(*) from ops.cargo_movement"), "the movement is kept");
        assertEquals(
                List.of("NEGATIVE_BALANCE " + out.eventId() + " 10.000 -15.000"),
                lines(
                        dataSource,
                        "select kind || ' ' || event_id || ' ' || (detail ->> 'balance') || ' ' || (detail ->> 'delta') "
                                + "from ops.reconciliation_item"));
        // the flag reaches the stream: the last inventory change says the row needs a look
        assertEquals(
                List.of("true"),
                lines(
                        dataSource,
                        "select body ->> 'needsReconciliation' from ops.state_change where entity_type = 'inventory' "
                                + "order by sequence desc limit 1"));
        assertTrue(snapshot().getBoolean("inventories[0].needsReconciliation"));
    }

    @Test
    void aBalanceAboveTheUsableCapacityIsKeptAsReportedAndOpensCapacityExceeded() {
        lot("itap:COAL-1", "DRY_BULK", "COAL", "TONNES", 500);
        h.process(h.events.lotMoved("itap:COAL-1", h.layout.berth(), h.layout.shed(), 120, "TONNES"));

        assertEquals(
                "120.000",
                lines(dataSource, "select quantity::text from ops.storage_inventory")
                        .get(0));
        assertEquals(
                List.of("CAPACITY_EXCEEDED 120.000 100.000"),
                lines(
                        dataSource,
                        "select kind || ' ' || (detail ->> 'stock') || ' ' || (detail ->> 'capacity') "
                                + "from ops.reconciliation_item"));
        assertEquals(1, count(dataSource, "select count(*) from ops.cargo_movement"));
        assertEquals("KNOWN", snapshot().getString("inventories[0].capacity.status"));
    }

    @Test
    void anAdjustmentIsAnImmutableRowAndChangesTheBalanceNotAnythingElse() throws SQLException {
        lot("itap:COAL-1", "DRY_BULK", "COAL", "TONNES", 50);
        h.process(h.events.lotMoved("itap:COAL-1", h.layout.berth(), h.layout.shed(), 40, "TONNES"));
        h.clock.step(10);
        h.process(h.events.inventoryAdjusted(h.layout.shed(), null, -2.5, "TONNES", "weighbridge recount"));

        assertEquals(
                "37.500",
                lines(dataSource, "select quantity::text from ops.storage_inventory")
                        .get(0));
        assertEquals(
                List.of("-2.500 weighbridge recount"),
                lines(dataSource, "select delta || ' ' || reason from ops.inventory_adjustment"));
        assertThrows(
                IllegalStateException.class,
                () -> count(
                        dataSource,
                        "with u as (update ops.inventory_adjustment set delta = 0 returning 1) select count(*) from u"));
        assertEquals(1, count(dataSource, "select count(*) from ops.cargo_movement"), "movements are not rewritten");
    }
}

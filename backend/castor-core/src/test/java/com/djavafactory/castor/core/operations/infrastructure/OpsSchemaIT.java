package com.djavafactory.castor.core.operations.infrastructure;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.support.LayoutTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Schema ops (V10): every table is there and the database itself refuses the rows the rules forbid. */
@QuarkusTest
class OpsSchemaIT {

    private static final List<String> TABLES = List.of(
            "vessel_call",
            "equipment_state",
            "equipment_status_interval",
            "cargo_lot",
            "cargo_movement",
            "storage_inventory",
            "inventory_adjustment",
            "gate_visit",
            "canonical_event",
            "processed_event",
            "state_change",
            "stream_cursor",
            "reconciliation_item",
            "dead_letter");

    @Inject
    DataSource dataSource;

    @BeforeEach
    @AfterEach
    void clear() {
        LayoutTestSupport.clear(dataSource);
    }

    private void run(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection();
                var s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private List<String> strings(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection();
                var rs = c.createStatement().executeQuery(sql)) {
            var out = new ArrayList<String>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        }
    }

    private UUID asset(String code, String type) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return LayoutTestSupport.asset(c, code, type);
        }
    }

    private long port() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return LayoutTestSupport.portId(c);
        }
    }

    private UUID lot(String code, String family) throws SQLException {
        UUID id = UUID.randomUUID();
        run("insert into ops.cargo_lot (id, port_id, lot_code, cargo_family, commodity, unit, source, quality) "
                + "values ('" + id + "', " + port() + ", '" + code + "', '" + family
                + "', 'COAL', 'TONNES', 'SCSS', 'OBSERVED')");
        return id;
    }

    private void movement(UUID lot, UUID to, UUID event) throws SQLException {
        run("insert into ops.cargo_movement (port_id, lot_id, to_id, quantity, unit, occurred_at, event_id) values ("
                + port() + ", '" + lot + "', '" + to + "', 5, 'TONNES', now(), '" + event + "')");
    }

    private void inventory(UUID pad, String quantity) throws SQLException {
        run("insert into ops.storage_inventory (port_id, storage_asset_id, commodity, unit, quantity, "
                + "quantity_observed_at, source, quality) values (" + port() + ", '" + pad + "', 'COAL', 'TONNES', "
                + quantity + ", now(), 'SCSS', 'OBSERVED')");
    }

    private void event(UUID id) throws SQLException {
        run(
                "insert into ops.canonical_event (event_id, port_id, type, aggregate_id, occurred_at, received_at, quality, body) "
                        + "values ('" + id + "', " + port()
                        + ", 'gate.transaction', 'g1', now(), now(), 'OBSERVED', '{}')");
    }

    @Test
    void everyOperationalTableCarriesAPortId() throws SQLException {
        for (String table : TABLES) {
            assertEquals(
                    List.of("port_id"),
                    strings("select column_name from information_schema.columns where table_schema = 'ops' "
                            + "and table_name = '" + table + "' and column_name = 'port_id'"),
                    table);
        }
    }

    @Test
    void cargoFamiliesAreSeededWithTheirDefaultUnit() throws SQLException {
        var rows = strings("select code || ':' || default_unit from ref.cargo_family order by code");
        assertTrue(
                rows.containsAll(
                        List.of("BREAKBULK:TONNES", "DRY_BULK:TONNES", "LIQUID_BULK:CUBIC_METRES", "RORO:VEHICLES")),
                rows.toString());
    }

    @Test
    void positionsHaveAGistIndex() throws SQLException {
        var defs =
                strings("select indexdef from pg_indexes where schemaname = 'ops' and tablename = 'equipment_state'");
        assertTrue(defs.stream().anyMatch(d -> d.contains("USING gist") && d.contains("position")), defs.toString());
    }

    @Test
    void negativeInventoryIsRejected() throws SQLException {
        UUID pad = asset("PAD1", "STORAGE_PAD");
        inventory(pad, "0");
        UUID other = asset("PAD2", "STORAGE_PAD");
        assertThrows(SQLException.class, () -> inventory(other, "-0.001"));
    }

    @Test
    void unknownCargoFamilyIsRejected() {
        assertThrows(SQLException.class, () -> lot("L1", "FRUIT"));
    }

    @Test
    void aNewCargoFamilyIsJustARow() throws SQLException {
        run("insert into ref.cargo_family (code, label, default_unit) values ('PROJECT', 'Project cargo', 'TONNES')");
        try {
            lot("L1", "PROJECT");
        } finally {
            LayoutTestSupport.clear(dataSource);
            run("delete from ref.cargo_family where code = 'PROJECT'");
        }
    }

    @Test
    void duplicateCargoMovementEventIdIsRejected() throws SQLException {
        UUID lot = lot("L1", "DRY_BULK");
        UUID pad = asset("PAD1", "STORAGE_PAD");
        UUID event = UUID.randomUUID();
        movement(lot, pad, event);
        assertThrows(SQLException.class, () -> movement(lot, pad, event));
    }

    @Test
    void updateOfACanonicalEventRowIsRejected() throws SQLException {
        UUID id = UUID.randomUUID();
        event(id);
        var update = assertThrows(SQLException.class, () -> run("update ops.canonical_event set type = 'x'"));
        assertTrue(update.getMessage().contains("immutable"), update.getMessage());
        assertThrows(SQLException.class, () -> run("delete from ops.canonical_event"));
    }

    @Test
    void movementsAndAdjustmentsAreImmutable() throws SQLException {
        UUID lot = lot("L1", "DRY_BULK");
        UUID pad = asset("PAD1", "STORAGE_PAD");
        movement(lot, pad, UUID.randomUUID());
        run(
                "insert into ops.inventory_adjustment (port_id, storage_asset_id, commodity, delta, unit, reason, event_id, "
                        + "occurred_at) values (" + port() + ", '" + pad + "', 'COAL', -2, 'TONNES', 'spill', '"
                        + UUID.randomUUID() + "', now())");
        assertThrows(SQLException.class, () -> run("update ops.cargo_movement set quantity = 1"));
        assertThrows(SQLException.class, () -> run("delete from ops.cargo_movement"));
        assertThrows(SQLException.class, () -> run("update ops.inventory_adjustment set delta = 1"));
        assertThrows(SQLException.class, () -> run("delete from ops.inventory_adjustment"));
    }

    @Test
    void enumColumnsAreChecked() throws SQLException {
        long port = port();
        assertThrows(
                SQLException.class,
                () -> run("insert into ops.vessel_call (port_id, call_code, status, source, "
                        + "quality, observed_at, last_occurred_at) values (" + port
                        + ", 'C1', 'SAILING', 's', 'OBSERVED', now(), now())"));
        assertThrows(
                SQLException.class,
                () -> run("insert into ops.vessel_call (port_id, call_code, status, source, "
                        + "quality, observed_at, last_occurred_at) values (" + port
                        + ", 'C1', 'EXPECTED', 's', 'GUESSED', now(), now())"));
        assertThrows(
                SQLException.class,
                () -> run("insert into ops.reconciliation_item (port_id, kind, entity_type, entity_id) " + "values ("
                        + port + ", 'WEIRD', 'lot', 'L1')"));
        assertThrows(
                SQLException.class,
                () -> run("insert into ops.cargo_lot (port_id, lot_code, cargo_family, commodity, "
                        + "unit, source, quality) values (" + port
                        + ", 'L1', 'DRY_BULK', 'COAL', 'FURLONGS', 's', 'OBSERVED')"));
    }

    @Test
    void callCodeIsUniquePerPort() throws SQLException {
        long port = port();
        String insert = "insert into ops.vessel_call (port_id, call_code, status, source, quality, observed_at, "
                + "last_occurred_at) values (" + port + ", 'C1', 'EXPECTED', 's', 'OBSERVED', now(), now())";
        run(insert);
        assertThrows(SQLException.class, () -> run(insert));
    }

    @Test
    void aPositionNeedsTheTimeItWasObserved() throws SQLException {
        UUID crane = asset("STS1", "STS_CRANE");
        assertThrows(
                SQLException.class,
                () -> run("insert into ops.equipment_state (port_id, asset_id, status, position, "
                        + "source, quality, observed_at, last_occurred_at) values (" + port() + ", '" + crane
                        + "', 'IDLE', "
                        + "ST_SetSRID(ST_MakePoint(101.3, 2.95), 4326), 's', 'OBSERVED', now(), now())"));
    }

    @Test
    void onlyOneStatusIntervalIsOpenPerMachine() throws SQLException {
        UUID crane = asset("STS1", "STS_CRANE");
        String open =
                "insert into ops.equipment_status_interval (port_id, asset_id, status, started_at, source, quality) "
                        + "values (" + port() + ", '" + crane + "', 'WORKING', now(), 's', 'OBSERVED')";
        run(open);
        assertThrows(SQLException.class, () -> run(open));
    }

    @Test
    void aResolvedReconciliationItemNamesWhoResolvedIt() throws SQLException {
        long port = port();
        assertThrows(
                SQLException.class,
                () -> run("insert into ops.reconciliation_item (port_id, kind, entity_type, "
                        + "entity_id, status) values (" + port + ", 'STALE_EVENT', 'lot', 'L1', 'RESOLVED')"));
    }

    @Test
    void roleOperatorIsAccepted() throws SQLException {
        run("insert into security.user_account (username, display_name, password_hash) values "
                + "('ops.schema', 'x', '$2a$12$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ01234')");
        try {
            run("insert into security.user_role (user_id, role) select id, 'OPERATOR' from security.user_account "
                    + "where username = 'ops.schema'");
        } finally {
            run("delete from security.user_account where username = 'ops.schema'");
        }
    }
}

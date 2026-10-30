package com.djavafactory.castor.core.operations.infrastructure;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.contracts.Quality;
import com.djavafactory.castor.contracts.Unit;
import com.djavafactory.castor.core.operations.domain.CargoProgress;
import com.djavafactory.castor.core.operations.domain.LineageEntry;
import com.djavafactory.castor.core.operations.domain.StateReadRepository;
import com.djavafactory.castor.core.operations.domain.StorageInventory;
import com.djavafactory.castor.core.operations.domain.VesselCall;
import com.djavafactory.castor.core.operations.domain.VesselStatus;

/** The snapshot and state reads. Runs in the transaction of the caller. */
@ApplicationScoped
public class JdbcStateReadRepository implements StateReadRepository {

    private static final String OVER = "('DEPARTED', 'CANCELLED')";

    private record Key(String family, Unit unit) {}

    private final OpsSql sql;

    JdbcStateReadRepository(OpsSql sql) {
        this.sql = sql;
    }

    @Override
    public void beginConsistentRead() {
        sql.update("set transaction isolation level repeatable read read only");
    }

    @Override
    public List<StorageInventory> inventories(long portId) {
        return sql.query(
                JdbcInventoryRepository.SELECT + "where port_id = ? order by storage_asset_id, commodity",
                JdbcInventoryRepository::map,
                portId);
    }

    @Override
    public long openGateVisits(long portId) {
        return count(
                "select count(*) as n from ops.gate_visit where port_id = ? and in_at is not null and out_at is null",
                portId);
    }

    @Override
    public long openReconciliationItems(long portId) {
        return count("select count(*) as n from ops.reconciliation_item where port_id = ? and status = 'OPEN'", portId);
    }

    private long count(String query, long portId) {
        return sql.queryOne(query, r -> r.lng("n"), portId).orElse(0L);
    }

    @Override
    public List<VesselCall> callsForSnapshot(long portId, Instant recentSince) {
        return sql.query(
                JdbcVesselCallRepository.SELECT + "where port_id = ? and (status not in " + OVER
                        + " or last_occurred_at >= ?) order by call_code",
                JdbcVesselCallRepository::map,
                portId,
                recentSince);
    }

    @Override
    public List<VesselCall> searchCalls(long portId, VesselStatus status, Instant from, Instant to, int limit) {
        var query = new StringBuilder(JdbcVesselCallRepository.SELECT).append("where port_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(portId);
        if (status != null) {
            query.append(" and status = ?");
            params.add(status);
        }
        if (from != null) {
            query.append(" and coalesce(atd, etd, 'infinity'::timestamptz) >= ?");
            params.add(from);
        }
        if (to != null) {
            query.append(" and coalesce(ata, eta, '-infinity'::timestamptz) <= ?");
            params.add(to);
        }
        query.append(" order by coalesce(ata, eta) desc nulls last, call_code limit ?");
        params.add(limit);
        return sql.query(query.toString(), JdbcVesselCallRepository::map, params.toArray());
    }

    @Override
    public Optional<VesselCall> findCall(long portId, UUID id) {
        return sql.queryOne(
                JdbcVesselCallRepository.SELECT + "where port_id = ? and id = ?",
                JdbcVesselCallRepository::map,
                portId,
                id);
    }

    @Override
    public boolean assetExists(long portId, UUID assetId) {
        return !sql.query("select 1 as one from layout.asset where port_id = ? and id = ?", r -> 1, portId, assetId)
                .isEmpty();
    }

    @Override
    public List<CargoProgress> cargoProgress(long portId, UUID berthAssetId, Instant from, Instant to) {
        // Registered: each lot that moved at the berth in the window counts once. Moved: every movement there.
        Map<Key, BigDecimal[]> byKey = new LinkedHashMap<>();
        sql.query(
                "select l.cargo_family, l.unit, sum(l.registered_quantity) as registered from ops.cargo_lot l "
                        + "where l.port_id = ? and l.id in (select m.lot_id from ops.cargo_movement m "
                        + "where m.port_id = ? and (m.from_id = ? or m.to_id = ?) and m.occurred_at >= ? "
                        + "and m.occurred_at <= ?) group by l.cargo_family, l.unit",
                r -> {
                    amounts(byKey, r.text("cargo_family"), r.text("unit"))[0] = r.decimal("registered");
                    return 1;
                },
                portId,
                portId,
                berthAssetId,
                berthAssetId,
                from,
                to);
        sql.query(
                "select l.cargo_family, m.unit, sum(m.quantity) as moved from ops.cargo_movement m "
                        + "join ops.cargo_lot l on l.id = m.lot_id where m.port_id = ? "
                        + "and (m.from_id = ? or m.to_id = ?) and m.occurred_at >= ? and m.occurred_at <= ? "
                        + "group by l.cargo_family, m.unit",
                r -> {
                    amounts(byKey, r.text("cargo_family"), r.text("unit"))[1] = r.decimal("moved");
                    return 1;
                },
                portId,
                berthAssetId,
                berthAssetId,
                from,
                to);
        return byKey.entrySet().stream()
                .map(e -> new CargoProgress(e.getKey().family(), e.getKey().unit(), e.getValue()[0], e.getValue()[1]))
                .sorted(Comparator.comparing(CargoProgress::cargoFamily)
                        .thenComparing(p -> p.unit().name()))
                .toList();
    }

    private static BigDecimal[] amounts(Map<Key, BigDecimal[]> byKey, String family, String unit) {
        return byKey.computeIfAbsent(new Key(family, Unit.parse(unit)), k -> new BigDecimal[2]);
    }

    @Override
    public List<LineageEntry> lineage(long portId, List<String> entityIds, int limit) {
        if (entityIds.isEmpty()) {
            return List.of();
        }
        String marks = String.join(", ", Collections.nCopies(entityIds.size(), "?"));
        List<Object> params = new ArrayList<>();
        params.add(portId);
        params.addAll(entityIds);
        params.addAll(entityIds);
        params.add(limit);
        return sql.query(
                "select type, occurred_at, coalesce(body ->> 'sourceSystem', 'unknown') as source, quality "
                        + "from ops.canonical_event where port_id = ? and (aggregate_id in (" + marks
                        + ") or body ->> 'entityId' in (" + marks + ")) "
                        + "order by occurred_at desc, received_at desc limit ?",
                r -> new LineageEntry(
                        r.text("type"), r.instant("occurred_at"), r.text("source"), Quality.valueOf(r.text("quality"))),
                params.toArray());
    }
}

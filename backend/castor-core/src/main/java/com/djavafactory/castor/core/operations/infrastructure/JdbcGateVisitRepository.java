package com.djavafactory.castor.core.operations.infrastructure;

import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.contracts.Quality;
import com.djavafactory.castor.core.operations.domain.GateVisit;
import com.djavafactory.castor.core.operations.domain.GateVisitRepository;

/** ops.gate_visit. Runs in the transaction of the caller. */
@ApplicationScoped
public class JdbcGateVisitRepository implements GateVisitRepository {

    private final OpsSql sql;

    JdbcGateVisitRepository(OpsSql sql) {
        this.sql = sql;
    }

    @Override
    public Optional<GateVisit> find(long portId, String truckVisitId, UUID gateAssetId) {
        return sql.queryOne(
                "select port_id, truck_visit_id, gate_asset_id, in_at, out_at, lane, source, quality, version "
                        + "from ops.gate_visit where port_id = ? and truck_visit_id = ? and gate_asset_id = ?",
                r -> new GateVisit(
                        r.lng("port_id"),
                        r.text("truck_visit_id"),
                        r.uuid("gate_asset_id"),
                        r.instant("in_at"),
                        r.instant("out_at"),
                        r.text("lane"),
                        r.text("source"),
                        Quality.valueOf(r.text("quality")),
                        r.lng("version")),
                portId,
                truckVisitId,
                gateAssetId);
    }

    @Override
    public GateVisit save(GateVisit v) {
        long version = sql.queryOne(
                        "insert into ops.gate_visit (port_id, truck_visit_id, gate_asset_id, in_at, out_at, lane, "
                                + "source, quality) values (?, ?, ?, ?, ?, ?, ?, ?) "
                                + "on conflict (port_id, truck_visit_id, gate_asset_id) do update set "
                                + "in_at = excluded.in_at, out_at = excluded.out_at, lane = excluded.lane, "
                                + "source = excluded.source, quality = excluded.quality, "
                                + "version = ops.gate_visit.version + 1 returning version",
                        r -> r.lng("version"),
                        v.portId(),
                        v.truckVisitId(),
                        v.gateAssetId(),
                        v.inAt(),
                        v.outAt(),
                        v.lane(),
                        v.source(),
                        v.quality())
                .orElseThrow();
        return new GateVisit(
                v.portId(),
                v.truckVisitId(),
                v.gateAssetId(),
                v.inAt(),
                v.outAt(),
                v.lane(),
                v.source(),
                v.quality(),
                version);
    }
}

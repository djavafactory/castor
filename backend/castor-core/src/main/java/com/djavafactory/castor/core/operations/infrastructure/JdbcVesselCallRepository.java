package com.djavafactory.castor.core.operations.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.contracts.Quality;
import com.djavafactory.castor.core.operations.domain.Provenance;
import com.djavafactory.castor.core.operations.domain.VesselCall;
import com.djavafactory.castor.core.operations.domain.VesselCallRepository;
import com.djavafactory.castor.core.operations.domain.VesselStatus;
import com.djavafactory.castor.core.shared.domain.ConflictException;

/** ops.vessel_call. Runs in the transaction of the caller. */
@ApplicationScoped
public class JdbcVesselCallRepository implements VesselCallRepository {

    static final String SELECT = "select id, port_id, call_code, vessel_name, imo, loa_m, beam_m, status, eta, "
            + "ata, etd, atd, berth_asset_id, source, quality, observed_at, last_occurred_at, version "
            + "from ops.vessel_call ";

    private final OpsSql sql;

    JdbcVesselCallRepository(OpsSql sql) {
        this.sql = sql;
    }

    @Override
    public Optional<VesselCall> find(long portId, String callCode) {
        return sql.queryOne(
                SELECT + "where port_id = ? and call_code = ?", JdbcVesselCallRepository::map, portId, callCode);
    }

    @Override
    public List<VesselCall> findAll(long portId) {
        return sql.query(SELECT + "where port_id = ? order by call_code", JdbcVesselCallRepository::map, portId);
    }

    @Override
    public VesselCall insert(VesselCall c) {
        UUID id = c.id() == null ? UUID.randomUUID() : c.id();
        sql.update(
                "insert into ops.vessel_call (id, port_id, call_code, vessel_name, imo, loa_m, beam_m, status, eta, ata, "
                        + "etd, atd, berth_asset_id, source, quality, observed_at, last_occurred_at, version) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                id,
                c.portId(),
                c.callCode(),
                c.vesselName(),
                c.imo(),
                c.loaM(),
                c.beamM(),
                c.status(),
                c.eta(),
                c.ata(),
                c.etd(),
                c.atd(),
                c.berthAssetId(),
                c.provenance().source(),
                c.provenance().quality(),
                c.provenance().observedAt(),
                c.lastOccurredAt());
        return new VesselCall(
                id,
                c.portId(),
                c.callCode(),
                c.vesselName(),
                c.imo(),
                c.loaM(),
                c.beamM(),
                c.status(),
                c.eta(),
                c.ata(),
                c.etd(),
                c.atd(),
                c.berthAssetId(),
                c.provenance(),
                c.lastOccurredAt(),
                0);
    }

    @Override
    public VesselCall update(VesselCall c) {
        int rows = sql.update(
                "update ops.vessel_call set vessel_name = ?, imo = ?, loa_m = ?, beam_m = ?, status = ?, eta = ?, "
                        + "ata = ?, etd = ?, atd = ?, berth_asset_id = ?, source = ?, quality = ?, observed_at = ?, "
                        + "last_occurred_at = ?, version = version + 1 where id = ? and version = ?",
                c.vesselName(),
                c.imo(),
                c.loaM(),
                c.beamM(),
                c.status(),
                c.eta(),
                c.ata(),
                c.etd(),
                c.atd(),
                c.berthAssetId(),
                c.provenance().source(),
                c.provenance().quality(),
                c.provenance().observedAt(),
                c.lastOccurredAt(),
                c.id(),
                c.version());
        if (rows != 1) {
            throw new ConflictException("Vessel call " + c.callCode() + " changed since version " + c.version());
        }
        return new VesselCall(
                c.id(),
                c.portId(),
                c.callCode(),
                c.vesselName(),
                c.imo(),
                c.loaM(),
                c.beamM(),
                c.status(),
                c.eta(),
                c.ata(),
                c.etd(),
                c.atd(),
                c.berthAssetId(),
                c.provenance(),
                c.lastOccurredAt(),
                c.version() + 1);
    }

    static VesselCall map(OpsSql.Row r) {
        return new VesselCall(
                r.uuid("id"),
                r.lng("port_id"),
                r.text("call_code"),
                r.text("vessel_name"),
                r.text("imo"),
                r.decimal("loa_m"),
                r.decimal("beam_m"),
                r.enumOf(VesselStatus.class, "status"),
                r.instant("eta"),
                r.instant("ata"),
                r.instant("etd"),
                r.instant("atd"),
                r.uuid("berth_asset_id"),
                new Provenance(r.text("source"), Quality.valueOf(r.text("quality")), r.instant("observed_at")),
                r.instant("last_occurred_at"),
                r.lng("version"));
    }
}

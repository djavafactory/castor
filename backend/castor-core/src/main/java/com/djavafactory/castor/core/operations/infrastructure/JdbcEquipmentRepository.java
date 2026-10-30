package com.djavafactory.castor.core.operations.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.contracts.Quality;
import com.djavafactory.castor.core.operations.domain.EquipmentRepository;
import com.djavafactory.castor.core.operations.domain.EquipmentState;
import com.djavafactory.castor.core.operations.domain.EquipmentStatus;
import com.djavafactory.castor.core.operations.domain.EquipmentStatusInterval;
import com.djavafactory.castor.core.operations.domain.Position;
import com.djavafactory.castor.core.operations.domain.Provenance;
import com.djavafactory.castor.core.shared.domain.ConflictException;

/** ops.equipment_state and ops.equipment_status_interval. Runs in the transaction of the caller. */
@ApplicationScoped
public class JdbcEquipmentRepository implements EquipmentRepository {

    private static final String SELECT = "select port_id, asset_id, status, status_reason, ST_X(position) as lon, "
            + "ST_Y(position) as lat, heading_deg, position_accuracy_m, position_observed_at, assignment, source, "
            + "quality, observed_at, last_occurred_at, last_source_sequence, version from ops.equipment_state ";

    private static final String INTERVAL_SELECT =
            "select port_id, asset_id, status, reason, started_at, ended_at, source, quality "
                    + "from ops.equipment_status_interval ";

    private final OpsSql sql;

    JdbcEquipmentRepository(OpsSql sql) {
        this.sql = sql;
    }

    @Override
    public Optional<EquipmentState> find(long portId, UUID assetId) {
        return sql.queryOne(
                SELECT + "where port_id = ? and asset_id = ?", JdbcEquipmentRepository::map, portId, assetId);
    }

    @Override
    public List<EquipmentState> findAll(long portId) {
        return sql.query(SELECT + "where port_id = ? order by asset_id", JdbcEquipmentRepository::map, portId);
    }

    @Override
    public EquipmentState insert(EquipmentState s) {
        Position p = s.position();
        sql.update(
                "insert into ops.equipment_state (port_id, asset_id, status, status_reason, position, heading_deg, "
                        + "position_accuracy_m, position_observed_at, assignment, source, quality, observed_at, "
                        + "last_occurred_at, last_source_sequence, version) "
                        + "values (?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?::float8, ?::float8), 4326), ?, ?, ?, "
                        + "?::jsonb, ?, ?, ?, ?, ?, 0)",
                s.portId(),
                s.assetId(),
                s.status(),
                s.statusReason(),
                p == null ? null : p.lon(),
                p == null ? null : p.lat(),
                p == null ? null : p.headingDeg(),
                p == null ? null : p.accuracyM(),
                p == null ? null : p.observedAt(),
                s.assignment(),
                s.provenance().source(),
                s.provenance().quality(),
                s.provenance().observedAt(),
                s.lastOccurredAt(),
                s.lastSourceSequence());
        return withVersion(s, 0);
    }

    @Override
    public EquipmentState update(EquipmentState s) {
        Position p = s.position();
        int rows = sql.update(
                "update ops.equipment_state set status = ?, status_reason = ?, "
                        + "position = ST_SetSRID(ST_MakePoint(?::float8, ?::float8), 4326), heading_deg = ?, "
                        + "position_accuracy_m = ?, position_observed_at = ?, assignment = ?::jsonb, source = ?, "
                        + "quality = ?, observed_at = ?, last_occurred_at = ?, last_source_sequence = ?, "
                        + "version = version + 1 where port_id = ? and asset_id = ? and version = ?",
                s.status(),
                s.statusReason(),
                p == null ? null : p.lon(),
                p == null ? null : p.lat(),
                p == null ? null : p.headingDeg(),
                p == null ? null : p.accuracyM(),
                p == null ? null : p.observedAt(),
                s.assignment(),
                s.provenance().source(),
                s.provenance().quality(),
                s.provenance().observedAt(),
                s.lastOccurredAt(),
                s.lastSourceSequence(),
                s.portId(),
                s.assetId(),
                s.version());
        if (rows != 1) {
            throw new ConflictException("Equipment " + s.assetId() + " changed since version " + s.version());
        }
        return withVersion(s, s.version() + 1);
    }

    @Override
    public Optional<EquipmentStatusInterval> openInterval(long portId, UUID assetId) {
        return sql.queryOne(
                INTERVAL_SELECT + "where port_id = ? and asset_id = ? and ended_at is null",
                JdbcEquipmentRepository::mapInterval,
                portId,
                assetId);
    }

    @Override
    public void startInterval(EquipmentStatusInterval i) {
        sql.update(
                "insert into ops.equipment_status_interval (port_id, asset_id, status, reason, started_at, ended_at, "
                        + "source, quality) values (?, ?, ?, ?, ?, ?, ?, ?)",
                i.portId(),
                i.assetId(),
                i.status(),
                i.reason(),
                i.startedAt(),
                i.endedAt(),
                i.source(),
                i.quality());
    }

    @Override
    public boolean closeInterval(long portId, UUID assetId, Instant endedAt) {
        return sql.update(
                        "update ops.equipment_status_interval set ended_at = ? "
                                + "where port_id = ? and asset_id = ? and ended_at is null",
                        endedAt,
                        portId,
                        assetId)
                == 1;
    }

    @Override
    public List<EquipmentStatusInterval> intervals(long portId, UUID assetId, int limit) {
        return sql.query(
                INTERVAL_SELECT + "where port_id = ? and asset_id = ? order by started_at desc, id desc limit ?",
                JdbcEquipmentRepository::mapInterval,
                portId,
                assetId,
                limit);
    }

    private static EquipmentState withVersion(EquipmentState s, long version) {
        return new EquipmentState(
                s.portId(),
                s.assetId(),
                s.status(),
                s.statusReason(),
                s.position(),
                s.assignment(),
                s.provenance(),
                s.lastOccurredAt(),
                s.lastSourceSequence(),
                version);
    }

    private static EquipmentState map(OpsSql.Row r) {
        Double lon = r.doubleOrNull("lon");
        Double lat = r.doubleOrNull("lat");
        Position position = lon == null || lat == null
                ? null
                : new Position(
                        lon,
                        lat,
                        r.decimal("heading_deg"),
                        r.decimal("position_accuracy_m"),
                        r.instant("position_observed_at"));
        return new EquipmentState(
                r.lng("port_id"),
                r.uuid("asset_id"),
                r.enumOf(EquipmentStatus.class, "status"),
                r.text("status_reason"),
                position,
                r.jsonObject("assignment"),
                new Provenance(r.text("source"), Quality.valueOf(r.text("quality")), r.instant("observed_at")),
                r.instant("last_occurred_at"),
                r.longOrNull("last_source_sequence"),
                r.lng("version"));
    }

    private static EquipmentStatusInterval mapInterval(OpsSql.Row r) {
        return new EquipmentStatusInterval(
                r.lng("port_id"),
                r.uuid("asset_id"),
                r.enumOf(EquipmentStatus.class, "status"),
                r.text("reason"),
                r.instant("started_at"),
                r.instant("ended_at"),
                r.text("source"),
                Quality.valueOf(r.text("quality")));
    }
}

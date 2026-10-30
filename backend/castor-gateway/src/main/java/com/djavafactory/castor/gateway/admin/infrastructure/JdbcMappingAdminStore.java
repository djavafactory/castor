package com.djavafactory.castor.gateway.admin.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.gateway.admin.domain.AdminException;
import com.djavafactory.castor.gateway.admin.domain.MappingAdminStore;
import com.djavafactory.castor.gateway.shared.infrastructure.Jdbc;

@ApplicationScoped
public class JdbcMappingAdminStore implements MappingAdminStore {

    /** exclusion_violation: the range overlaps another row of the same source id. */
    private static final String EXCLUSION_VIOLATION = "23P01";

    private static final String COLUMNS = "id, source_system, source_entity_type, source_id, canonical_id, "
            + "effective_from, effective_to, created_by, created_at";
    private static final String INSERT = "insert into ingest.identity_mapping (port_id, source_system, "
            + "source_entity_type, source_id, canonical_id, effective_from, effective_to, created_by, created_at) "
            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning " + COLUMNS;
    private static final String LOCK =
            "select " + COLUMNS + " from ingest.identity_mapping where id = ? and port_id = ? for update";
    private static final String CLOSE =
            "update ingest.identity_mapping set effective_to = ? where id = ? returning " + COLUMNS;
    private static final String UNRESOLVED = "select i.source, q.subject_type, q.subject_id, q.reason_code, count(*), "
            + "max(q.created_at) from ingest.quarantine q join ingest.inbox i on i.reception_id = q.reception_id "
            + "where i.port_id = ? and q.reason_code in ('UNKNOWN_MAPPING', 'AMBIGUOUS_MAPPING') "
            + "and q.subject_id is not null and i.replay_of is null "
            + "and not exists (select 1 from ingest.inbox r where r.replay_of = i.reception_id and r.status = 'NORMALIZED') "
            + "group by i.source, q.subject_type, q.subject_id, q.reason_code "
            + "order by count(*) desc, i.source, q.subject_id";

    private final Jdbc jdbc;

    public JdbcMappingAdminStore(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<MappingEntry> list(String portId, MappingFilter filter) {
        StringBuilder sql = new StringBuilder("select " + COLUMNS + " from ingest.identity_mapping where port_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(portId);
        if (filter.source() != null) {
            sql.append(" and source_system = ?");
            args.add(filter.source());
        }
        if (filter.entityType() != null) {
            sql.append(" and source_entity_type = ?");
            args.add(filter.entityType());
        }
        if (filter.sourceId() != null) {
            sql.append(" and source_id ilike ? escape '\\'");
            args.add("%"
                    + filter.sourceId()
                            .replace("\\", "\\\\")
                            .replace("%", "\\%")
                            .replace("_", "\\_") + "%");
        }
        switch (filter.state()) {
            case "open" -> sql.append(" and effective_to is null");
            case "closed" -> sql.append(" and effective_to is not null");
            default -> {}
        }
        sql.append(" order by source_system, source_entity_type, source_id, effective_from, id limit ?");
        args.add(filter.limit());
        return jdbc.read(c -> {
            try (var ps = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < args.size(); i++) {
                    ps.setObject(i + 1, args.get(i));
                }
                try (var rs = ps.executeQuery()) {
                    List<MappingEntry> rows = new ArrayList<>();
                    while (rs.next()) {
                        rows.add(entry(rs));
                    }
                    return rows;
                }
            }
        });
    }

    @Override
    public MappingEntry create(
            String portId,
            String sourceSystem,
            String entityType,
            String sourceId,
            String canonicalId,
            Instant effectiveFrom,
            Instant effectiveTo,
            String createdBy,
            Instant now) {
        try {
            return jdbc.inTransaction(c -> {
                try (var ps = c.prepareStatement(INSERT)) {
                    ps.setString(1, portId);
                    ps.setString(2, sourceSystem);
                    ps.setString(3, entityType);
                    ps.setString(4, sourceId);
                    ps.setString(5, canonicalId);
                    ps.setTimestamp(6, Timestamp.from(effectiveFrom));
                    ps.setTimestamp(7, effectiveTo == null ? null : Timestamp.from(effectiveTo));
                    ps.setString(8, createdBy);
                    ps.setTimestamp(9, Timestamp.from(now));
                    try (var rs = ps.executeQuery()) {
                        rs.next();
                        return entry(rs);
                    }
                }
            });
        } catch (Jdbc.DatabaseException e) {
            if (e.getCause() instanceof SQLException sql && EXCLUSION_VIOLATION.equals(sql.getSQLState())) {
                throw AdminException.conflict(
                        "MAPPING_OVERLAP",
                        "Another mapping of " + sourceSystem + " " + entityType + " '" + sourceId
                                + "' is valid in that period: close it first");
            }
            throw e;
        }
    }

    @Override
    public MappingEntry close(String portId, long id, Instant effectiveTo) {
        return jdbc.inTransaction(c -> {
            try (var ps = c.prepareStatement(LOCK)) {
                ps.setLong(1, id);
                ps.setString(2, portId);
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw AdminException.notFound("MAPPING_NOT_FOUND", "No such mapping");
                    }
                    MappingEntry row = entry(rs);
                    if (row.effectiveTo() != null) {
                        throw AdminException.conflict("MAPPING_CLOSED", "The mapping is closed already");
                    }
                    if (!effectiveTo.isAfter(row.effectiveFrom())) {
                        throw AdminException.invalid(
                                "END_BEFORE_START", "The end must be after the start " + row.effectiveFrom());
                    }
                }
            }
            try (var ps = c.prepareStatement(CLOSE)) {
                ps.setTimestamp(1, Timestamp.from(effectiveTo));
                ps.setLong(2, id);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return entry(rs);
                }
            }
        });
    }

    @Override
    public List<UnresolvedId> unresolved(String portId) {
        return jdbc.read(c -> {
            try (var ps = c.prepareStatement(UNRESOLVED)) {
                ps.setString(1, portId);
                try (var rs = ps.executeQuery()) {
                    List<UnresolvedId> ids = new ArrayList<>();
                    while (rs.next()) {
                        ids.add(new UnresolvedId(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getString(4),
                                rs.getLong(5),
                                rs.getTimestamp(6).toInstant()));
                    }
                    return ids;
                }
            }
        });
    }

    private static MappingEntry entry(ResultSet rs) throws SQLException {
        Timestamp to = rs.getTimestamp(7);
        return new MappingEntry(
                rs.getLong(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getTimestamp(6).toInstant(),
                to == null ? null : to.toInstant(),
                rs.getString(8),
                rs.getTimestamp(9).toInstant());
    }
}

package com.djavafactory.castor.gateway.mapping.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.contracts.Unit;
import com.djavafactory.castor.gateway.mapping.domain.MappingRow;
import com.djavafactory.castor.gateway.mapping.domain.MappingStore;
import com.djavafactory.castor.gateway.shared.infrastructure.Jdbc;

@ApplicationScoped
public class JdbcMappingStore implements MappingStore {

    private static final String IDENTITY =
            "select canonical_id, effective_from, effective_to from ingest.identity_mapping "
                    + "where port_id = ? and source_system = ? and source_entity_type = ? and source_id = ?";
    private static final String CARGO =
            "select canonical_id, effective_from, effective_to from ingest.cargo_code_mapping "
                    + "where source_system = ? and source_entity_type = ? and source_id = ?";
    private static final String UNITS = "select unit_code from ingest.cargo_family_unit where family_code = ?";
    private static final String ASSETS = "select code, asset_id::text from layout.published_asset where port_code = ?";
    private static final String INSERT = "insert into ingest.identity_mapping (port_id, source_system, "
            + "source_entity_type, source_id, canonical_id, effective_from, created_by, created_at) "
            + "select ?, ?, ?, ?, ?, ?, ?, ? where not exists (select 1 from ingest.identity_mapping "
            + "where port_id = ? and source_system = ? and source_entity_type = ? and source_id = ?)";

    /** undefined_table and insufficient_privilege: the view or the grant of castor-core's V11 is not there yet. */
    private static final Set<String> VIEW_UNAVAILABLE = Set.of("42P01", "42501", "3F000");

    private final Jdbc jdbc;

    public JdbcMappingStore(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<MappingRow> identity(String portId, String sourceSystem, String entityType, String sourceId) {
        return jdbc.read(c -> {
            try (var ps = c.prepareStatement(IDENTITY)) {
                ps.setString(1, portId);
                ps.setString(2, sourceSystem);
                ps.setString(3, entityType);
                ps.setString(4, sourceId);
                return rows(ps.executeQuery());
            }
        });
    }

    @Override
    public List<MappingRow> cargo(String sourceSystem, String entityType, String sourceId) {
        return jdbc.read(c -> {
            try (var ps = c.prepareStatement(CARGO)) {
                ps.setString(1, sourceSystem);
                ps.setString(2, entityType);
                ps.setString(3, sourceId);
                return rows(ps.executeQuery());
            }
        });
    }

    @Override
    public Set<Unit> unitsOf(String familyCode) {
        return jdbc.read(c -> {
            try (var ps = c.prepareStatement(UNITS)) {
                ps.setString(1, familyCode);
                try (var rs = ps.executeQuery()) {
                    Set<Unit> units = EnumSet.noneOf(Unit.class);
                    while (rs.next()) {
                        units.add(Unit.parse(rs.getString(1)));
                    }
                    return units;
                }
            }
        });
    }

    @Override
    public Map<String, String> publishedAssets(String portId) {
        try {
            return jdbc.read(c -> {
                try (var ps = c.prepareStatement(ASSETS)) {
                    ps.setString(1, portId);
                    try (var rs = ps.executeQuery()) {
                        Map<String, String> assets = new HashMap<>();
                        while (rs.next()) {
                            assets.put(rs.getString(1), rs.getString(2));
                        }
                        return assets;
                    }
                }
            });
        } catch (Jdbc.DatabaseException e) {
            if (e.getCause() instanceof SQLException sql && VIEW_UNAVAILABLE.contains(sql.getSQLState())) {
                throw new AssetDirectoryUnavailableException(sql.getMessage(), sql);
            }
            throw e;
        }
    }

    @Override
    public boolean insertIfMissing(
            String portId,
            String sourceSystem,
            String entityType,
            String sourceId,
            String canonicalId,
            Instant effectiveFrom,
            String createdBy,
            Instant createdAt) {
        return jdbc.inTransaction(c -> {
            try (var ps = c.prepareStatement(INSERT)) {
                ps.setString(1, portId);
                ps.setString(2, sourceSystem);
                ps.setString(3, entityType);
                ps.setString(4, sourceId);
                ps.setString(5, canonicalId);
                ps.setTimestamp(6, Timestamp.from(effectiveFrom));
                ps.setString(7, createdBy);
                ps.setTimestamp(8, Timestamp.from(createdAt));
                ps.setString(9, portId);
                ps.setString(10, sourceSystem);
                ps.setString(11, entityType);
                ps.setString(12, sourceId);
                return ps.executeUpdate() == 1;
            }
        });
    }

    private static List<MappingRow> rows(ResultSet rs) throws SQLException {
        try (rs) {
            List<MappingRow> rows = new ArrayList<>();
            while (rs.next()) {
                Timestamp to = rs.getTimestamp(3);
                rows.add(new MappingRow(
                        rs.getString(1), rs.getTimestamp(2).toInstant(), to == null ? null : to.toInstant()));
            }
            return rows;
        }
    }
}

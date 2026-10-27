package com.djavafactory.castor.core.layout.infrastructure;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.djavafactory.castor.core.layout.domain.AssetType;
import com.djavafactory.castor.core.layout.domain.AssetType.Flags;
import com.djavafactory.castor.core.layout.domain.AssetType.Sizes;
import com.djavafactory.castor.core.layout.domain.AssetTypeRepository;

/** Reads layout.asset_type with plain SQL. */
@ApplicationScoped
public class JdbcAssetTypeRepository implements AssetTypeRepository {

    private static final String SQL = "select t.code, t.label, t.palette_group_code, t.category_code, "
            + "t.geometry_kind, t.placement_mode, t.is_mobile, t.is_solid, t.is_flat, t.is_zone, "
            + "t.may_extend_over_water, t.must_be_on_berth, "
            + "t.default_length_m, t.default_width_m, t.default_height_m, t.default_radius_m, t.default_line_width_m, "
            + "t.min_length_m, t.min_width_m, t.min_height_m, t.min_radius_m, "
            + "t.max_length_m, t.max_width_m, t.max_height_m, t.max_radius_m, "
            + "t.default_capacity_value, t.default_capacity_unit, t.params::text, t.model_key, t.model_rev, "
            + "t.colour_hex, t.hint, t.sort_order "
            + "from layout.asset_type t join ref.palette_group g on g.code = t.palette_group_code "
            + "order by g.sort_order, t.sort_order";

    private static final TypeReference<Map<String, Object>> PARAMS = new TypeReference<>() {};

    private final DataSource dataSource;
    private final ObjectMapper mapper;

    public JdbcAssetTypeRepository(DataSource dataSource, ObjectMapper mapper) {
        this.dataSource = dataSource;
        this.mapper = mapper;
    }

    @Override
    public List<AssetType> findAll() {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(SQL);
                ResultSet rs = ps.executeQuery()) {
            List<AssetType> types = new ArrayList<>();
            while (rs.next()) {
                types.add(map(rs));
            }
            return types;
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Reading the asset types failed", e);
        }
    }

    private AssetType map(ResultSet rs) throws SQLException, IOException {
        return new AssetType(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                new Flags(
                        rs.getBoolean(7),
                        rs.getBoolean(8),
                        rs.getBoolean(9),
                        rs.getBoolean(10),
                        rs.getBoolean(11),
                        rs.getBoolean(12)),
                sizes(rs, 13, true),
                sizes(rs, 18, false),
                sizes(rs, 22, false),
                rs.getBigDecimal(26),
                rs.getString(27),
                mapper.readValue(rs.getString(28), PARAMS),
                rs.getString(29),
                rs.getInt(30),
                rs.getString(31),
                rs.getString(32),
                rs.getInt(33));
    }

    /** Reads length, width, height, radius (and line width when asked) starting at the given column. */
    private static Sizes sizes(ResultSet rs, int first, boolean withLineWidth) throws SQLException {
        BigDecimal lineWidth = withLineWidth ? rs.getBigDecimal(first + 4) : null;
        return new Sizes(
                rs.getBigDecimal(first),
                rs.getBigDecimal(first + 1),
                rs.getBigDecimal(first + 2),
                rs.getBigDecimal(first + 3),
                lineWidth);
    }
}

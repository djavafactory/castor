package com.djavafactory.castor.core.geometry.infrastructure;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.djavafactory.castor.core.geometry.domain.LonLat;
import com.djavafactory.castor.core.geometry.domain.Measurements;

/** Measures with PostGIS: every figure goes through ST_Transform(geom, measure_srid). */
@ApplicationScoped
public class PostgisMeasurements implements Measurements {

    private static final String PROJECTED = "ST_Transform(ST_GeomFromText(?, 4326), ?)";

    private final DataSource dataSource;

    @Inject
    public PostgisMeasurements(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public double lengthM(List<LonLat> line, int measureSrid) {
        return number("SELECT ST_Length(" + PROJECTED + ")", wkt(line, false), measureSrid);
    }

    @Override
    public double areaM2(List<LonLat> ring, int measureSrid) {
        return number("SELECT ST_Area(" + PROJECTED + ")", wkt(ring, true), measureSrid);
    }

    @Override
    public double perimeterM(List<LonLat> ring, int measureSrid) {
        return number("SELECT ST_Perimeter(" + PROJECTED + ")", wkt(ring, true), measureSrid);
    }

    @Override
    public double distanceM(LonLat a, LonLat b, int measureSrid) {
        return number(
                "SELECT ST_Distance(" + PROJECTED + ", " + PROJECTED + ")",
                point(a),
                measureSrid,
                point(b),
                measureSrid);
    }

    @Override
    public LonLat roundTrip(LonLat p, int measureSrid) {
        var sql = "SELECT ST_X(g), ST_Y(g) FROM (SELECT ST_Transform(" + PROJECTED + ", 4326) AS g) t";
        try (var c = dataSource.getConnection();
                var ps = c.prepareStatement(sql)) {
            ps.setString(1, point(p));
            ps.setInt(2, measureSrid);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return new LonLat(rs.getDouble(1), rs.getDouble(2));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Projection round trip failed", e);
        }
    }

    /** Runs a query returning one number; arguments are bound in order (String WKT, Integer SRID, ...). */
    private double number(String sql, Object... args) {
        try (var c = dataSource.getConnection();
                var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Integer srid) {
                    ps.setInt(i + 1, srid);
                } else {
                    ps.setString(i + 1, (String) args[i]);
                }
            }
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getDouble(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Measurement failed", e);
        }
    }

    private static String point(LonLat p) {
        return "POINT(" + coordinate(p) + ")";
    }

    private static String wkt(List<LonLat> points, boolean polygon) {
        var all = new ArrayList<>(points);
        if (polygon) {
            all.add(points.get(0));
        }
        var coords = all.stream().map(PostgisMeasurements::coordinate).collect(Collectors.joining(", "));
        return polygon ? "POLYGON((" + coords + "))" : "LINESTRING(" + coords + ")";
    }

    private static String coordinate(LonLat p) {
        return String.format(Locale.ROOT, "%.10f %.10f", p.lon(), p.lat());
    }
}

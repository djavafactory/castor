package com.djavafactory.castor.core.layout.domain;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;

import com.djavafactory.castor.core.geometry.domain.CoordinateRounding;
import com.djavafactory.castor.core.geometry.domain.LonLat;
import com.djavafactory.castor.core.geometry.domain.QuayFrame;
import com.djavafactory.castor.core.geometry.domain.QuayShapes;

/**
 * Derives the stored footprint of POINT, RECT and CIRCLE assets from anchor, heading and size, in the metric quay
 * frame (CORE-01 shapes), rounded to 7 decimals by CORE-01. The mapping layer never rounds.
 */
public final class Footprints {

    public static final int SRID = 4326;
    public static final GeometryFactory FACTORY = new GeometryFactory(new PrecisionModel(), SRID);

    private Footprints() {}

    public static Polygon rect(QuayFrame frame, LonLat anchor, double headingDeg, double lengthM, double widthM) {
        return polygon(QuayShapes.rect(frame, anchor, headingDeg, lengthM, widthM));
    }

    public static Polygon circle(QuayFrame frame, LonLat anchor, double radiusM) {
        return polygon(QuayShapes.circle(frame, anchor, radiusM));
    }

    /**
     * The geometry to store for an asset. POINT, RECT and CIRCLE types always get the footprint rebuilt from anchor,
     * heading and size, so it can never drift from its fields; every other type keeps the geometry as drawn.
     */
    public static Geometry derive(AssetType type, LayoutAsset asset, QuayFrame frame) {
        Geometry geometry = switch (type.geometryKind()) {
            case "POINT", "RECT" ->
                rect(
                        frame,
                        asset.anchor(),
                        asset.headingDeg() == null ? 0 : asset.headingDeg().doubleValue(),
                        asset.lengthM().doubleValue(),
                        asset.widthM().doubleValue());
            case "CIRCLE" -> circle(frame, asset.anchor(), asset.radiusM().doubleValue());
            default -> asset.geom();
        };
        geometry.setSRID(SRID);
        return geometry;
    }

    /** A counter-clockwise closed ring from an open list of corners. */
    public static Polygon polygon(List<LonLat> openRing) {
        var rounded = CoordinateRounding.round(openRing).points();
        var coords = new ArrayList<Coordinate>(rounded.size() + 1);
        for (LonLat p : rounded) {
            coords.add(new Coordinate(p.lon(), p.lat()));
        }
        coords.add(coords.get(0));
        return FACTORY.createPolygon(coords.toArray(Coordinate[]::new));
    }
}

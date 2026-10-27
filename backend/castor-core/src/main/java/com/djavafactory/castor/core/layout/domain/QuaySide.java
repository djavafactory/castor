package com.djavafactory.castor.core.layout.domain;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;

/**
 * Which side of the quay line is the water: the side the land extent is not on. Worked out in the metric quay frame,
 * point by point against the nearest piece of the quay line, so a bent quay works too.
 */
final class QuaySide {

    /** A point closer to the quay line than this is on the quay, not in the water (the 1 cm tolerance of SETUP-03). */
    static final double TOLERANCE_M = 0.01;

    private record Segment(Coordinate a, Coordinate b, int landSign) {}

    private final List<Segment> segments = new ArrayList<>();

    private QuaySide() {}

    /** Null when the port has no quay line or no land extent to tell the sides apart. */
    static QuaySide of(Site site) {
        if (site.quayLine() == null || site.landExtent() == null || !(site.quayLine() instanceof LineString line)) {
            return null;
        }
        Coordinate land =
                Local.of(site.landExtent().getInteriorPoint(), site.frame()).getCoordinate();
        Geometry local = Local.of(line, site.frame());
        Coordinate[] pts = local.getCoordinates();
        var side = new QuaySide();
        for (int i = 0; i + 1 < pts.length; i++) {
            int sign = (int) Math.signum(cross(pts[i], pts[i + 1], land));
            if (sign != 0) {
                side.segments.add(new Segment(pts[i], pts[i + 1], sign));
            }
        }
        return side.segments.isEmpty() ? null : side;
    }

    /** True when the local point lies on the water side, further than the tolerance from the quay line. */
    boolean isWater(Coordinate p) {
        Segment nearest = null;
        double best = Double.MAX_VALUE;
        for (Segment s : segments) {
            double d = distance(s, p);
            if (d < best) {
                best = d;
                nearest = s;
            }
        }
        if (nearest == null || best <= TOLERANCE_M) {
            return false;
        }
        return Math.signum(cross(nearest.a, nearest.b, p)) != nearest.landSign;
    }

    private static double cross(Coordinate a, Coordinate b, Coordinate p) {
        return (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x);
    }

    private static double distance(Segment s, Coordinate p) {
        double dx = s.b.x - s.a.x;
        double dy = s.b.y - s.a.y;
        double t = ((p.x - s.a.x) * dx + (p.y - s.a.y) * dy) / (dx * dx + dy * dy);
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(p.x - (s.a.x + t * dx), p.y - (s.a.y + t * dy));
    }
}

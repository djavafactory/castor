package com.djavafactory.castor.core.layout.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;

/**
 * Counting and crossing checks on outlines. {@link #crossingEdges} is the same algorithm as selfIntersection() in the
 * frontend (lib/geo/intersect.ts) so the drawing tool and the server name the same pair of edges.
 */
final class Outlines {

    private static final double EPS = 1e-9;

    private Outlines() {}

    /** Distinct corners of a polygon (closing point not counted) or distinct points of a line. */
    static int distinctPoints(Geometry g) {
        Coordinate[] coords = g instanceof Polygon p ? p.getExteriorRing().getCoordinates() : g.getCoordinates();
        var seen = new HashSet<List<Double>>();
        for (Coordinate c : coords) {
            seen.add(List.of(c.x, c.y));
        }
        return seen.size();
    }

    /** The open exterior ring of a polygon, in the order drawn. */
    static List<Coordinate> openRing(Polygon p) {
        Coordinate[] ring = p.getExteriorRing().getCoordinates();
        var out = new ArrayList<Coordinate>(ring.length);
        for (int i = 0; i < ring.length - 1; i++) {
            out.add(ring[i]);
        }
        return out;
    }

    /**
     * First pair of edges [i, j] (0-based, i &lt; j) of a closed ring (open ring input) that cross, or null. Edge k runs
     * from point k to point k + 1; the last edge closes the ring. Neighbouring edges share a vertex by definition, so
     * they only count when the second edge doubles back over the first.
     */
    static int[] crossingEdges(List<Coordinate> pts) {
        int n = pts.size();
        if (n < 4) {
            return null;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                Coordinate a = pts.get(i);
                Coordinate b = pts.get((i + 1) % n);
                Coordinate c = pts.get(j);
                Coordinate d = pts.get((j + 1) % n);
                boolean forward = j == i + 1;
                boolean wraps = i == 0 && j == n - 1;
                if (forward || wraps) {
                    Coordinate shared = forward ? b : a;
                    Coordinate e1 = forward ? a : b;
                    Coordinate e2 = forward ? d : c;
                    if (Math.abs(cross(shared, e1, e2)) <= EPS
                            && (withinBox(shared, e1, e2) || withinBox(shared, e2, e1))) {
                        return new int[] {i, j};
                    }
                    continue;
                }
                if (segmentsIntersect(a, b, c, d)) {
                    return new int[] {i, j};
                }
            }
        }
        return null;
    }

    private static boolean segmentsIntersect(Coordinate a, Coordinate b, Coordinate c, Coordinate d) {
        double d1 = cross(c, d, a);
        double d2 = cross(c, d, b);
        double d3 = cross(a, b, c);
        double d4 = cross(a, b, d);
        if (((d1 > EPS && d2 < -EPS) || (d1 < -EPS && d2 > EPS))
                && ((d3 > EPS && d4 < -EPS) || (d3 < -EPS && d4 > EPS))) {
            return true;
        }
        return (Math.abs(d1) <= EPS && withinBox(c, d, a))
                || (Math.abs(d2) <= EPS && withinBox(c, d, b))
                || (Math.abs(d3) <= EPS && withinBox(a, b, c))
                || (Math.abs(d4) <= EPS && withinBox(a, b, d));
    }

    private static double cross(Coordinate o, Coordinate a, Coordinate b) {
        return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x);
    }

    private static boolean withinBox(Coordinate a, Coordinate b, Coordinate p) {
        return Math.min(a.x, b.x) - EPS <= p.x
                && p.x <= Math.max(a.x, b.x) + EPS
                && Math.min(a.y, b.y) - EPS <= p.y
                && p.y <= Math.max(a.y, b.y) + EPS;
    }
}

package it.replica8.castor.core.geometry.domain;

import java.util.List;

/**
 * Java measurement in the local quay frame. Test helper only: production measurements go through PostGIS
 * ST_Transform (PostgisMeasurements), so this exists to cross-check them.
 */
public final class LocalMetrics {

    private LocalMetrics() {}

    public static double length(QuayFrame frame, List<LonLat> line) {
        double total = 0;
        for (int i = 1; i < line.size(); i++) {
            total += distance(frame, line.get(i - 1), line.get(i));
        }
        return total;
    }

    public static double perimeter(QuayFrame frame, List<LonLat> ring) {
        double total = length(frame, ring);
        return total + distance(frame, ring.get(ring.size() - 1), ring.get(0));
    }

    public static double area(QuayFrame frame, List<LonLat> ring) {
        double sum = 0;
        for (int i = 0; i < ring.size(); i++) {
            LocalPoint a = frame.toLocal(ring.get(i));
            LocalPoint b = frame.toLocal(ring.get((i + 1) % ring.size()));
            sum += a.x() * b.y() - b.x() * a.y();
        }
        return Math.abs(sum) / 2;
    }

    public static double distance(QuayFrame frame, LonLat a, LonLat b) {
        LocalPoint p = frame.toLocal(a);
        LocalPoint q = frame.toLocal(b);
        return Math.hypot(p.x() - q.x(), p.y() - q.y());
    }
}

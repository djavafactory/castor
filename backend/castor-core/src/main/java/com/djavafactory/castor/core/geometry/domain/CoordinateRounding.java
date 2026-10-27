package com.djavafactory.castor.core.geometry.domain;

import java.util.ArrayList;
import java.util.List;

/** Canonical rounding of stored coordinates: 7 decimals (1e-7 degree, about 1 cm). */
public final class CoordinateRounding {

    private static final double SCALE = 1e7;

    private CoordinateRounding() {}

    /** The rounded positions and the largest move any of them made, in millimetres. */
    public record Rounded(List<LonLat> points, double maxAdjustmentMm) {}

    public static double round7(double degrees) {
        return Math.round(degrees * SCALE) / SCALE;
    }

    public static Rounded round(List<LonLat> points) {
        var out = new ArrayList<LonLat>(points.size());
        double maxMm = 0;
        for (LonLat p : points) {
            var r = new LonLat(round7(p.lon()), round7(p.lat()));
            double dxM = (r.lon() - p.lon()) * QuayFrame.metresPerDegreeLon(p.lat());
            double dyM = (r.lat() - p.lat()) * QuayFrame.metresPerDegreeLat(p.lat());
            maxMm = Math.max(maxMm, Math.hypot(dxM, dyM) * 1000);
            out.add(r);
        }
        return new Rounded(List.copyOf(out), maxMm);
    }
}

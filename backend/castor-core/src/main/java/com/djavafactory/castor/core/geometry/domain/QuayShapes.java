package com.djavafactory.castor.core.geometry.domain;

import java.util.ArrayList;
import java.util.List;

import com.djavafactory.castor.core.shared.domain.RuleViolation;
import com.djavafactory.castor.core.shared.domain.RuleViolationException;

/** Builds rectangles and circles in the metric quay frame and returns them in WGS84. Rings are open. */
public final class QuayShapes {

    public static final int CIRCLE_SEGMENTS = 48;

    private QuayShapes() {}

    /**
     * Four corners of a rectangle, counter-clockwise from the (-length/2, -width/2) corner.
     *
     * @param anchor centre of the rectangle
     * @param headingDeg true heading of the length axis, clockwise from north
     */
    public static List<LonLat> rect(QuayFrame frame, LonLat anchor, double headingDeg, double length, double width) {
        positive(length, "/length");
        positive(width, "/width");
        LocalPoint a = frame.toLocal(anchor);
        double rot = Math.toRadians(frame.headingToRotation(headingDeg));
        double c = Math.cos(rot);
        double s = Math.sin(rot);
        double hl = length / 2;
        double hw = width / 2;
        double[][] corners = {{-hl, -hw}, {hl, -hw}, {hl, hw}, {-hl, hw}};
        var ring = new ArrayList<LonLat>(4);
        for (double[] k : corners) {
            ring.add(frame.toLonLat(a.x() + k[0] * c - k[1] * s, a.y() + k[0] * s + k[1] * c));
        }
        return ring;
    }

    public static List<LonLat> circle(QuayFrame frame, LonLat anchor, double radius) {
        return circle(frame, anchor, radius, CIRCLE_SEGMENTS);
    }

    public static List<LonLat> circle(QuayFrame frame, LonLat anchor, double radius, int segments) {
        positive(radius, "/radius");
        if (segments < 3) {
            throw new RuleViolationException(
                    "Invalid circle", List.of(new RuleViolation("SEGMENTS", null, "/segments", "At least 3")));
        }
        LocalPoint a = frame.toLocal(anchor);
        var ring = new ArrayList<LonLat>(segments);
        for (int i = 0; i < segments; i++) {
            double t = 2 * Math.PI * i / segments;
            ring.add(frame.toLonLat(a.x() + radius * Math.cos(t), a.y() + radius * Math.sin(t)));
        }
        return ring;
    }

    private static void positive(double v, String pointer) {
        if (!(v > 0)) {
            throw new RuleViolationException(
                    "Invalid shape", List.of(new RuleViolation("NOT_POSITIVE", null, pointer, "Must be above 0")));
        }
    }
}

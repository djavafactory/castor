package com.djavafactory.castor.core.layout.domain;

import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.CoordinateSequenceFilter;
import org.locationtech.jts.geom.Geometry;

import com.djavafactory.castor.core.geometry.domain.QuayFrame;

/** Copies of WGS84 geometries in the metric quay frame (x along the quay, y inland), where distances are metres. */
final class Local {

    private Local() {}

    static Geometry of(Geometry wgs84, QuayFrame frame) {
        Geometry copy = wgs84.copy();
        copy.apply(new CoordinateSequenceFilter() {
            @Override
            public void filter(CoordinateSequence seq, int i) {
                var p = frame.toLocal(seq.getX(i), seq.getY(i));
                seq.setOrdinate(i, 0, p.x());
                seq.setOrdinate(i, 1, p.y());
            }

            @Override
            public boolean isDone() {
                return false;
            }

            @Override
            public boolean isGeometryChanged() {
                return true;
            }
        });
        copy.geometryChanged();
        return copy;
    }
}

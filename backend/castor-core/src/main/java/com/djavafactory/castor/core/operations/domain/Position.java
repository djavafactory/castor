package com.djavafactory.castor.core.operations.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** A WGS84 fix with the time it was observed. Heading and accuracy are null when the source does not say. */
public record Position(double lon, double lat, BigDecimal headingDeg, BigDecimal accuracyM, Instant observedAt) {

    public Position {
        if (lon < -180 || lon > 180 || lat < -90 || lat > 90) {
            throw new IllegalArgumentException("Not a WGS84 position: " + lon + ", " + lat);
        }
        if (observedAt == null) {
            throw new IllegalArgumentException("observedAt is required");
        }
    }
}

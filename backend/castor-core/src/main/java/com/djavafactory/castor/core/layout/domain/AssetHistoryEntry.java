package com.djavafactory.castor.core.layout.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * How one asset looked in one version. The measures come from the geography of the stored shape.
 *
 * @param geometryType PostGIS name of the shape, for example POLYGON or LINESTRING
 */
public record AssetHistoryEntry(
        UUID versionId,
        int versionNo,
        LayoutStatus status,
        String name,
        String geometryType,
        double areaM2,
        double lengthM,
        String changedBy,
        Instant changedAt) {

    /** "Polygon, 1,234 m2" or "Line, 120 m": enough to see that a shape moved or grew between versions. */
    public String geometrySummary() {
        if ("LINESTRING".equals(geometryType)) {
            return String.format(Locale.ROOT, "Line, %,.0f m", lengthM);
        }
        return String.format(Locale.ROOT, "Polygon, %,.0f m2", areaM2);
    }
}

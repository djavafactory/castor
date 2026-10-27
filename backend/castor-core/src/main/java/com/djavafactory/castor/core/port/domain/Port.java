package com.djavafactory.castor.core.port.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import com.djavafactory.castor.core.geometry.domain.LonLat;

/**
 * The port and its coordinate frame. Geometries are GeoJSON text exactly as the database produced it.
 * The UTC offset is derived from the zone, never stored.
 */
public record Port(
        long id,
        String code,
        String name,
        String city,
        String timeZone,
        LonLat origin,
        int measureSrid,
        double frameRotDeg,
        String extent,
        String landExtent,
        String quayLine,
        boolean synthetic,
        UUID publishedLayoutVersionId) {

    /** The offset of the port's time zone at the given instant, for example +08:00. */
    public String utcOffset(Instant at) {
        String id = ZoneId.of(timeZone).getRules().getOffset(at).getId();
        return "Z".equals(id) ? "+00:00" : id;
    }
}

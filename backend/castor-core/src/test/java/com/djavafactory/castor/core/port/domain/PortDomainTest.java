package com.djavafactory.castor.core.port.domain;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.geometry.domain.LonLat;
import com.djavafactory.castor.core.shared.domain.InvalidRequestException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PortDomainTest {

    private static Port port(String zone) {
        return new Port(
                1,
                "westports",
                "Westports",
                "Port Klang",
                zone,
                new LonLat(101.3, 2.95),
                3380,
                -120.299,
                "{}",
                null,
                null,
                true,
                (UUID) null);
    }

    @Test
    void utcOffsetIsDerivedFromTheTimeZone() {
        Instant now = Instant.parse("2026-06-01T00:00:00Z");
        assertEquals("+08:00", port("Asia/Kuala_Lumpur").utcOffset(now));
        assertEquals("+00:00", port("UTC").utcOffset(now));
        assertEquals("-05:00", port("America/Bogota").utcOffset(now));
    }

    @Test
    void parsesABoundingBox() {
        var box = BoundingBox.parse("101.26, 2.90,101.36,3.02");
        assertEquals(new BoundingBox(101.26, 2.90, 101.36, 3.02), box);
    }

    @Test
    void refusesMalformedBoundingBoxes() {
        assertThrows(InvalidRequestException.class, () -> BoundingBox.parse("1,2,3"));
        assertThrows(InvalidRequestException.class, () -> BoundingBox.parse("a,b,c,d"));
        assertThrows(InvalidRequestException.class, () -> BoundingBox.parse("102,2.9,101,3"));
        assertThrows(InvalidRequestException.class, () -> BoundingBox.parse("101,2.9,102,95"));
    }
}

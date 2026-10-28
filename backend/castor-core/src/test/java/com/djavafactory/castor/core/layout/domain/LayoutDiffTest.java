package com.djavafactory.castor.core.layout.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.support.ShapeFixtures.Asset;

import static com.djavafactory.castor.core.support.ShapeFixtures.rect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayoutDiffTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();

    private static LayoutAsset hall(UUID id, String name, double x0, Double height) {
        return Asset.of(id, "WAREHOUSE", name, rect(x0, 0, x0 + 10, 10))
                .height(height)
                .build();
    }

    @Test
    void sameAssetsHaveNoDifference() {
        var diff = LayoutDiff.compare(List.of(hall(A, "Hall A", 0, 5.0)), List.of(hall(A, "Hall A", 0, 5.0)));

        assertTrue(diff.isEmpty());
    }

    @Test
    void addedRemovedAndChangedAreSeenFromTheVersionBeingLookedAt() {
        var now = List.of(hall(A, "Hall A", 50, 5.0), hall(C, "Hall C", 100, null));
        var old = List.of(hall(A, "Hall A", 0, 5.0), hall(B, "Hall B", 20, null));

        var diff = LayoutDiff.compare(now, old);

        assertEquals(
                List.of(C), diff.added().stream().map(LayoutDiff.Entry::assetId).toList());
        assertEquals(
                List.of(B),
                diff.removed().stream().map(LayoutDiff.Entry::assetId).toList());
        assertEquals(1, diff.changed().size());
        assertEquals(List.of("geometry"), diff.changed().get(0).fields());
    }

    @Test
    void everyChangedPropertyIsNamedAndDecimalScaleIsIgnored() {
        var a = hall(A, "Hall A", 0, 5.0);
        var b = hall(A, "Hall A renamed", 0, 6.5);

        assertEquals(
                List.of("name", "heightM"),
                LayoutDiff.compare(List.of(b), List.of(a)).changed().get(0).fields());
        assertTrue(LayoutDiff.compare(List.of(hall(A, "Hall A", 0, 5.00)), List.of(hall(A, "Hall A", 0, 5.0)))
                .isEmpty());
    }

    @Test
    void geometrySummaryNamesTheKindAndSize() {
        var area = new AssetHistoryEntry(
                UUID.randomUUID(), 2, LayoutStatus.PUBLISHED, "Hall", "POLYGON", 1234.4, 0, "pat", Instant.now());
        var line = new AssetHistoryEntry(
                UUID.randomUUID(), 1, LayoutStatus.SUPERSEDED, "Berth", "LINESTRING", 0, 120.2, "pat", Instant.now());

        assertEquals("Polygon, 1,234 m2", area.geometrySummary());
        assertEquals("Line, 120 m", line.geometrySummary());
    }
}

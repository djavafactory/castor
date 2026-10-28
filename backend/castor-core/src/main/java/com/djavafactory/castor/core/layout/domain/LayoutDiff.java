package com.djavafactory.castor.core.layout.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.locationtech.jts.geom.Geometry;

/**
 * What differs between two versions of one port, matched by asset id. Added and removed are seen from the version
 * that is looked at: added are in it but not in the other, removed are in the other but not in it.
 */
public record LayoutDiff(List<Entry> added, List<Entry> removed, List<Changed> changed) {

    /** Shapes closer than this (degrees, about a millimetre) count as the same shape. */
    private static final double SAME_SHAPE = 1e-8;

    public record Entry(UUID assetId, String code, String name, String typeCode) {}

    /** @param fields names of the changed properties, for example geometry or heightM */
    public record Changed(UUID assetId, String code, String name, List<String> fields) {}

    public boolean isEmpty() {
        return added.isEmpty() && removed.isEmpty() && changed.isEmpty();
    }

    public static LayoutDiff compare(List<LayoutAsset> version, List<LayoutAsset> against) {
        Map<UUID, LayoutAsset> before = new HashMap<>();
        against.forEach(a -> before.put(a.id(), a));
        Map<UUID, LayoutAsset> after = new HashMap<>();
        version.forEach(a -> after.put(a.id(), a));

        List<Entry> added = new ArrayList<>();
        List<Changed> changed = new ArrayList<>();
        for (LayoutAsset a : version) {
            LayoutAsset old = before.get(a.id());
            if (old == null) {
                added.add(entry(a));
                continue;
            }
            List<String> fields = changedFields(a, old);
            if (!fields.isEmpty()) {
                changed.add(new Changed(a.id(), a.code(), a.name(), fields));
            }
        }
        List<Entry> removed = against.stream()
                .filter(a -> !after.containsKey(a.id()))
                .map(LayoutDiff::entry)
                .toList();
        return new LayoutDiff(added, removed, changed);
    }

    private static Entry entry(LayoutAsset a) {
        return new Entry(a.id(), a.code(), a.name(), a.typeCode());
    }

    private static List<String> changedFields(LayoutAsset now, LayoutAsset old) {
        List<String> out = new ArrayList<>();
        check(out, "name", Objects.equals(now.name(), old.name()));
        check(out, "geometry", sameShape(now.geom(), old.geom()));
        check(out, "headingDeg", same(now.headingDeg(), old.headingDeg()));
        check(out, "lengthM", same(now.lengthM(), old.lengthM()));
        check(out, "widthM", same(now.widthM(), old.widthM()));
        check(out, "heightM", same(now.heightM(), old.heightM()));
        check(out, "radiusM", same(now.radiusM(), old.radiusM()));
        check(out, "lineWidthM", same(now.lineWidthM(), old.lineWidthM()));
        check(out, "elevationM", same(now.elevationM(), old.elevationM()));
        check(out, "clearanceM", same(now.clearanceM(), old.clearanceM()));
        check(out, "storageZoneCode", Objects.equals(now.storageZoneCode(), old.storageZoneCode()));
        check(out, "operatorName", Objects.equals(now.operatorName(), old.operatorName()));
        check(out, "description", Objects.equals(now.description(), old.description()));
        check(out, "provenance", Objects.equals(now.provenance(), old.provenance()));
        check(out, "details", Objects.equals(now.details(), old.details()));
        check(out, "links", sameLinks(now, old));
        return out;
    }

    private static void check(List<String> out, String field, boolean same) {
        if (!same) {
            out.add(field);
        }
    }

    private static boolean same(BigDecimal a, BigDecimal b) {
        return a == null || b == null ? a == b : a.compareTo(b) == 0;
    }

    private static boolean sameShape(Geometry a, Geometry b) {
        return a == null || b == null ? a == b : a.equalsExact(b, SAME_SHAPE);
    }

    private static boolean sameLinks(LayoutAsset a, LayoutAsset b) {
        Function<LayoutAsset, Set<LayoutAsset.Link>> links = x -> Set.copyOf(x.links());
        return links.apply(a).equals(links.apply(b));
    }
}

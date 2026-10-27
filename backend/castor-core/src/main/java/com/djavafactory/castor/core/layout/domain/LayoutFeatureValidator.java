package com.djavafactory.castor.core.layout.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;

import com.djavafactory.castor.core.geometry.domain.LonLat;
import com.djavafactory.castor.core.geometry.domain.QuayFrame;
import com.djavafactory.castor.core.shared.domain.RuleViolation;
import com.djavafactory.castor.core.shared.domain.RuleViolationException;

/**
 * The layout rules every feature must pass before it is stored, whether it comes from the seed, an import or the
 * designer. It checks what the database enforces (code, geometry kind, extent) plus what only the domain knows: sizes
 * inside the asset type limits, a footprint that matches anchor, heading and size, required details and resolvable
 * links.
 */
public final class LayoutFeatureValidator {

    public static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9._/-]{0,39}$");
    /** The stored footprint must equal the one rebuilt from its fields within this distance, metres. */
    public static final double FOOTPRINT_TOLERANCE_M = 0.01;

    /** Sources whose imagery must never be traced into stored geometry (ref.data_source with may_trace and may_persist false). */
    public static final Set<String> UNTRACEABLE_SOURCES = Set.of("GOOGLE");

    private static final Set<String> LINK_KINDS = Set.of("SERVES", "FEEDS", "BELONGS_TO", "CONNECTS_TO");

    private final Map<String, AssetType> types;
    private final QuayFrame frame;
    private final Geometry extent;

    public LayoutFeatureValidator(Map<String, AssetType> types, QuayFrame frame, Geometry extent) {
        this.types = types;
        this.frame = frame;
        this.extent = extent;
    }

    /** Validates the whole set; returns every violation, empty when the set is clean. */
    public List<RuleViolation> validate(List<FeatureDraft> drafts) {
        var out = new ArrayList<RuleViolation>();
        var codes = new HashSet<String>();
        for (FeatureDraft d : drafts) {
            if (!codes.add(d.code())) {
                out.add(v("DUPLICATE_CODE", d, "/properties/code", "Code is used twice in the layout"));
            }
        }
        for (FeatureDraft d : drafts) {
            validateFeature(d, codes, out);
        }
        return out;
    }

    public void requireValid(List<FeatureDraft> drafts) {
        var violations = validate(drafts);
        if (!violations.isEmpty()) {
            throw new RuleViolationException("Layout validation failed (" + violations.size() + " errors)", violations);
        }
    }

    private void validateFeature(FeatureDraft d, Set<String> codes, List<RuleViolation> out) {
        LayoutAsset a = d.asset();
        if (a.code() == null || !CODE.matcher(a.code()).matches()) {
            out.add(v("CODE_FORMAT", d, "/properties/code", "Code must match " + CODE.pattern()));
        }
        if (a.name() == null || a.name().isBlank()) {
            out.add(v("NAME_REQUIRED", d, "/properties/name", "Name is required"));
        }
        if (a.provenance() != null
                && UNTRACEABLE_SOURCES.contains(a.provenance().source())) {
            out.add(v(
                    "SOURCE_NOT_TRACEABLE",
                    d,
                    "/properties/provenance/source",
                    "Google Maps is a locator only: its imagery cannot be traced into stored geometry."));
        }
        AssetType type = types.get(a.typeCode());
        if (type == null) {
            out.add(v("UNKNOWN_TYPE", d, "/properties/type", "Unknown asset type " + a.typeCode()));
            return;
        }
        if (a.headingDeg() != null
                && (a.headingDeg().signum() < 0 || a.headingDeg().compareTo(BigDecimal.valueOf(360)) >= 0)) {
            out.add(v("HEADING_RANGE", d, "/properties/headingDeg", "Heading must be from 0 up to 360"));
        }
        if (a.geom() == null || a.geom().isEmpty() || !a.geom().isValid()) {
            out.add(v("GEOMETRY_INVALID", d, "/geometry", "Geometry is empty or not valid"));
            return;
        }
        if (!checkKind(d, type, out)) {
            return;
        }
        if (extent != null && !extent.covers(a.geom())) {
            out.add(v("OUTSIDE_EXTENT", d, "/geometry", "Geometry lies outside the port extent"));
        }
        checkSizes(d, type, out);
        checkFootprint(d, type, out);
        checkDetails(d, type, out);
        for (FeatureDraft.DraftLink l : d.links()) {
            if (!LINK_KINDS.contains(l.kind())) {
                out.add(v("LINK_KIND", d, "/properties/links", "Unknown link kind " + l.kind()));
            }
            if (!codes.contains(l.toCode())) {
                out.add(v("LINK_TARGET", d, "/properties/links", "Link target " + l.toCode() + " does not exist"));
            }
            if (a.code().equals(l.toCode())) {
                out.add(v("LINK_SELF", d, "/properties/links", "An asset cannot link to itself"));
            }
        }
    }

    private boolean checkKind(FeatureDraft d, AssetType type, List<RuleViolation> out) {
        Geometry g = d.asset().geom();
        boolean line = "LINE".equals(type.geometryKind()) || "QUAY_SEGMENT".equals(type.geometryKind());
        boolean ok = line ? g instanceof LineString : g instanceof Polygon;
        if (!ok) {
            out.add(v("GEOMETRY_KIND", d, "/geometry", type.code() + " needs a " + (line ? "LineString" : "Polygon")));
        }
        return ok;
    }

    private void checkSizes(FeatureDraft d, AssetType type, List<RuleViolation> out) {
        LayoutAsset a = d.asset();
        switch (type.geometryKind()) {
            case "POINT", "RECT" -> {
                limit(
                        d,
                        "lengthM",
                        a.lengthM(),
                        type.min().lengthM(),
                        type.max().lengthM(),
                        true,
                        out);
                limit(d, "widthM", a.widthM(), type.min().widthM(), type.max().widthM(), true, out);
            }
            case "CIRCLE" ->
                limit(d, "radiusM", a.radiusM(), BigDecimal.ONE, type.max().radiusM(), true, out);
            default -> {}
        }
        limit(d, "heightM", a.heightM(), null, type.max().heightM(), false, out);
    }

    private void limit(
            FeatureDraft d,
            String field,
            BigDecimal value,
            BigDecimal min,
            BigDecimal max,
            boolean required,
            List<RuleViolation> out) {
        String pointer = "/properties/" + field;
        if (value == null) {
            if (required) {
                out.add(v(
                        "SIZE_REQUIRED",
                        d,
                        pointer,
                        field + " is required for " + d.asset().typeCode()));
            }
            return;
        }
        if (value.signum() <= 0
                || (min != null && value.compareTo(min) < 0)
                || (max != null && value.compareTo(max) > 0)) {
            out.add(v(
                    "SIZE_RANGE",
                    d,
                    pointer,
                    field + " " + value + " is outside the limits of "
                            + d.asset().typeCode()));
        }
    }

    /** A POINT, RECT or CIRCLE footprint must equal the one rebuilt from anchor, heading and size. */
    private void checkFootprint(FeatureDraft d, AssetType type, List<RuleViolation> out) {
        LayoutAsset a = d.asset();
        Polygon expected;
        try {
            expected = switch (type.geometryKind()) {
                case "POINT", "RECT" ->
                    Footprints.rect(
                            frame,
                            a.anchor(),
                            a.headingDeg() == null ? 0 : a.headingDeg().doubleValue(),
                            a.lengthM().doubleValue(),
                            a.widthM().doubleValue());
                case "CIRCLE" ->
                    Footprints.circle(frame, a.anchor(), a.radiusM().doubleValue());
                default -> null;
            };
        } catch (RuntimeException e) {
            return; // a missing size is already reported
        }
        if (expected == null) {
            return;
        }
        double off = maxVertexDistanceM(expected, (Polygon) a.geom());
        if (off > FOOTPRINT_TOLERANCE_M) {
            out.add(v(
                    "FOOTPRINT_MISMATCH",
                    d,
                    "/geometry",
                    String.format(
                            "Footprint differs from anchor, heading and size by %.3f m (limit %.2f m)",
                            off, FOOTPRINT_TOLERANCE_M)));
        }
    }

    /** Largest distance in metres between corresponding vertices; different vertex counts never match. */
    private double maxVertexDistanceM(Polygon expected, Polygon stored) {
        Coordinate[] e = expected.getExteriorRing().getCoordinates();
        Coordinate[] s = stored.getExteriorRing().getCoordinates();
        if (e.length != s.length) {
            return Double.POSITIVE_INFINITY;
        }
        double max = 0;
        for (int i = 0; i < e.length; i++) {
            var p = frame.toLocal(new LonLat(e[i].x, e[i].y));
            var q = frame.toLocal(new LonLat(s[i].x, s[i].y));
            max = Math.max(max, Math.hypot(p.x() - q.x(), p.y() - q.y()));
        }
        return max;
    }

    private void checkDetails(FeatureDraft d, AssetType type, List<RuleViolation> out) {
        AssetDetails x = d.asset().details();
        switch (type.code()) {
            case "BERTH" -> need(d, x.berth(), "berth", out);
            case "STORAGE_PAD" -> checkPad(d, x, out);
            case "CONTAINER_YARD" -> need(d, x.containerYard(), "containerYard", out);
            case "EXCLUSION_ZONE" -> need(d, x.zone(), "zone", out);
            case "CONVEYOR", "PIPELINE" -> need(d, x.flow(), "flow", out);
            case "SURFACE_AREA" -> need(d, x.surface(), "surface", out);
            default -> {}
        }
        if (type.flags().mobile()) {
            need(d, x.mobile(), "mobile", out);
        }
        if (x.zone() != null) {
            for (String allowed : x.zone().allowedTypes()) {
                if (!types.containsKey(allowed)) {
                    out.add(v("ZONE_TYPE", d, "/properties/details/zone", "Unknown allowed type " + allowed));
                }
            }
        }
    }

    private void checkPad(FeatureDraft d, AssetDetails x, List<RuleViolation> out) {
        need(d, x.storagePad(), "storagePad", out);
        LayoutAsset a = d.asset();
        if (x.storagePad() == null || a.lengthM() == null || a.widthM() == null) {
            return;
        }
        double[] f = StoragePadCapacity.footprint(x.storagePad());
        if (Math.abs(f[0] - a.lengthM().doubleValue()) > 0.001
                || Math.abs(f[1] - a.widthM().doubleValue()) > 0.001) {
            out.add(v(
                    "PAD_FOOTPRINT",
                    d,
                    "/properties/details/storagePad",
                    "Pad size must be bays x bay pitch by rows x row pitch"));
        }
    }

    private void need(FeatureDraft d, Object detail, String key, List<RuleViolation> out) {
        if (detail == null) {
            out.add(v(
                    "DETAIL_REQUIRED",
                    d,
                    "/properties/details/" + key,
                    d.asset().typeCode() + " needs details." + key));
        }
    }

    private static RuleViolation v(String code, FeatureDraft d, String pointer, String message) {
        return new RuleViolation(code, d.code(), pointer, message);
    }
}

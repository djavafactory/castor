package com.djavafactory.castor.core.layout.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.valid.IsValidOp;
import org.locationtech.jts.operation.valid.TopologyValidationError;

import com.djavafactory.castor.core.layout.domain.PlacementFacts.Berth;
import com.djavafactory.castor.core.layout.domain.PlacementFacts.Candidate;
import com.djavafactory.castor.core.layout.domain.PlacementFacts.Contact;

import static com.djavafactory.castor.core.layout.domain.ValidationMessages.message;
import static com.djavafactory.castor.core.layout.domain.ValidationMessages.number;
import static com.djavafactory.castor.core.layout.domain.ValidationMessages.variant;

/**
 * The geometry and placement rule catalogue of README section 6, with two entry points that apply the very same rules:
 * {@link #validateAsset} for the save of one shape and {@link #validateLayout} for publishing and the Issues list.
 *
 * <p>Distances and areas that decide a rule are measured in metres: in the quay frame for single shapes and in the
 * measurement CRS of the port inside PostGIS for shapes that meet ({@link PlacementFacts}). Placement of one shape is
 * checked in a fixed order and the first failure is the one reported: ON_WATER, OUTSIDE_LAND, IN_EXCLUSION_ZONE, then
 * OVERLAP (BERTH_OVERLAP for berths, CRANE_OFF_BERTH for rail quay cranes). Shape rules (type, source, geometry, size)
 * come first and are all reported.
 */
public final class LayoutValidator {

    /** Drawn polygons smaller than this are not shapes but slips of the mouse. */
    public static final double MIN_DRAWN_AREA_M2 = 4;
    /** Solid shapes may share at most this much area (digitising noise). */
    public static final double OVERLAP_AREA_M2 = 0.01;
    /** Berths may share at most this much quay. */
    public static final double BERTH_OVERLAP_M = 0.05;
    /** A bay or row pitch below this cannot hold a pile of bulk cargo. */
    public static final double MIN_PILE_SLOT_M = 10;
    /** A storage pad's bay and row pitch may not be below this (the editor's minimum). */
    public static final double MIN_PAD_PITCH_M = 5;
    /** A rail quay crane stands within this distance of the berth line (gauge 16 m plus the rail and apron). */
    public static final double RAIL_CRANE_MAX_OFFSET_M = 30;

    /** Types that declare an area rather than occupy it: they are never "in" an exclusion zone. */
    private static final Set<String> AREA_DECLARATIONS = Set.of("TERMINAL_BOUNDARY", "SURFACE_AREA");

    private static final String ACCURACY_APPROXIMATE = "APPROXIMATE";

    private final LayoutRepository layouts;
    private final Map<String, AssetType> types;
    private final Site site;
    private final Set<String> untraceableSources;
    private final PlacementFacts facts;
    private final QuaySide quay;
    private final Geometry landLocal;

    /**
     * @param untraceableSources codes of data sources whose imagery must never be traced into stored shapes
     */
    public LayoutValidator(
            LayoutRepository layouts,
            Map<String, AssetType> types,
            Site site,
            Set<String> untraceableSources,
            PlacementFacts facts) {
        this.layouts = layouts;
        this.types = types;
        this.site = site;
        this.untraceableSources = untraceableSources;
        this.facts = facts;
        this.quay = QuaySide.of(site);
        this.landLocal = site.landExtent() == null
                ? null
                : Local.of(site.landExtent(), site.frame()).buffer(QuaySide.TOLERANCE_M);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // One shape
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Checks a shape before it is saved into the draft. The candidate may be new (id null) or a changed copy of an
     * asset of the draft; it is never compared with its own stored revision.
     *
     * @return errors first; a report with errors means the save must be refused
     */
    public ValidationReport validateAsset(LayoutVersion draft, LayoutAsset candidate) {
        var out = new ArrayList<ValidationIssue>();
        Subject subject = shapeRules(candidate, true, out);
        if (subject != null) {
            var contacts =
                    facts.contacts(draft.id(), new Candidate(candidate.id(), candidate.typeCode(), candidate.geom()));
            List<LocalBerth> berths = subject.needsBerths() ? localBerths(facts.berths(draft.id())) : List.of();
            out.addAll(placementErrors(subject, contacts, berths, null, Map.of()));
            out.addAll(placementWarnings(subject, contacts, Map.of()));
        }
        return report(out);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The whole layout
    // ---------------------------------------------------------------------------------------------------------------

    /** Checks every shape of a version and the rules that look at the layout as a whole. */
    public ValidationReport validateLayout(LayoutVersion version) {
        List<LayoutAsset> assets = layouts.load(version).assets().stream()
                .sorted(Comparator.comparing(LayoutAsset::code))
                .toList();
        Map<UUID, String> names = new LinkedHashMap<>();
        assets.forEach(a -> names.put(a.id(), displayName(a)));
        var contacts = new LinkedHashMap<UUID, List<Contact>>();
        for (Contact c : facts.contacts(version.id(), null)) {
            contacts.computeIfAbsent(c.subjectId(), k -> new ArrayList<>()).add(c);
        }
        List<LocalBerth> berths = localBerths(assets.stream()
                .filter(a -> types.get(a.typeCode()) != null
                        && "QUAY_SEGMENT".equals(types.get(a.typeCode()).geometryKind()))
                .map(a -> new Berth(a.id(), displayName(a), a.geom()))
                .toList());

        var out = new ArrayList<ValidationIssue>();
        var reportedPairs = new HashSet<String>();
        var approximate = new ArrayList<String>();
        for (LayoutAsset a : assets) {
            Subject subject = shapeRules(a, false, out);
            if (a.provenance() != null
                    && ACCURACY_APPROXIMATE.equals(a.provenance().accuracy())) {
                approximate.add(a.id().toString());
            }
            if (subject == null) {
                continue;
            }
            var mine = contacts.getOrDefault(a.id(), List.of());
            out.addAll(placementErrors(subject, mine, berths, reportedPairs, names));
            out.addAll(placementWarnings(subject, mine, names));
        }
        if (!approximate.isEmpty()) {
            out.add(new ValidationIssue(
                    "APPROXIMATE_SOURCE",
                    IssueLevel.WARNING,
                    approximate.size() == 1
                            ? variant("APPROXIMATE_SOURCE", "one")
                            : variant("APPROXIMATE_SOURCE", "many", "n", String.valueOf(approximate.size())),
                    approximate));
        }
        return report(out);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Shape rules: type, source, geometry, size
    // ---------------------------------------------------------------------------------------------------------------

    /** The shape and where it stands, ready for the placement rules. Null when the shape is not usable. */
    private record Subject(LayoutAsset asset, AssetType type, String name, Geometry local) {

        UUID id() {
            return asset.id();
        }

        boolean needsBerths() {
            return type.flags().mustBeOnBerth() || "QUAY_SEGMENT".equals(type.geometryKind());
        }
    }

    private record LocalBerth(UUID id, String name, Geometry line, double minX, double maxX) {}

    private Subject shapeRules(LayoutAsset a, boolean single, List<ValidationIssue> out) {
        String name = displayName(a);
        AssetType type = types.get(a.typeCode());
        if (type == null) {
            out.add(error(
                    "UNKNOWN_TYPE", message("UNKNOWN_TYPE", "name", name, "type", String.valueOf(a.typeCode())), a));
            return null;
        }
        if (a.provenance() != null && untraceableSources.contains(a.provenance().source())) {
            out.add(error("TRACE_SOURCE_NOT_ALLOWED", message("TRACE_SOURCE_NOT_ALLOWED", "name", name), a));
        }
        Geometry local = geometryRules(a, type, name, single, out);
        dimensionRules(a, type, local, out);
        detailRules(a, out);
        return local == null ? null : new Subject(a, type, name, local);
    }

    /** Kind, point count, validity, extent and size of the outline; returns it in the quay frame when all pass. */
    private Geometry geometryRules(
            LayoutAsset a, AssetType type, String name, boolean single, List<ValidationIssue> out) {
        Geometry g = a.geom();
        if (g == null || g.isEmpty()) {
            out.add(error("GEOM_INVALID", message("GEOM_INVALID", "name", name), a));
            return null;
        }
        boolean line = isLine(type);
        boolean kindOk = line ? g instanceof LineString : g instanceof Polygon;
        if (!kindOk) {
            out.add(error("WRONG_GEOMETRY_KIND", message("WRONG_GEOMETRY_KIND", "name", name, "type", type.code()), a));
            return null;
        }
        if (Outlines.distinctPoints(g) < (line ? 2 : 3)) {
            out.add(error("TOO_FEW_POINTS", variant("TOO_FEW_POINTS", line ? "line" : "polygon", "name", name), a));
            return null;
        }
        Geometry local = Local.of(g, site.frame());
        TopologyValidationError invalid = new IsValidOp(g).getValidationError();
        if (invalid != null) {
            out.add(invalidShape(a, name, invalid, local, single));
            return null;
        }
        if (site.extent() != null && !site.extent().covers(g)) {
            out.add(error("OUT_OF_PORT", message("OUT_OF_PORT", "name", name), a));
            return null;
        }
        boolean drawnPolygon = "POLYGON".equals(type.geometryKind()) && "DRAW".equals(type.placementMode());
        if (drawnPolygon && local.getArea() < MIN_DRAWN_AREA_M2) {
            out.add(error("TOO_SMALL", message("TOO_SMALL"), a));
        }
        return local;
    }

    private ValidationIssue invalidShape(
            LayoutAsset a, String name, TopologyValidationError invalid, Geometry local, boolean single) {
        boolean crossing = invalid.getErrorType() == TopologyValidationError.SELF_INTERSECTION
                || invalid.getErrorType() == TopologyValidationError.RING_SELF_INTERSECTION;
        if (!crossing) {
            return error("GEOM_INVALID", message("GEOM_INVALID", "name", name), a);
        }
        int[] edges = local instanceof Polygon p ? Outlines.crossingEdges(Outlines.openRing(p)) : null;
        if (single && edges != null) {
            return error(
                    "SELF_INTERSECTION",
                    variant(
                            "SELF_INTERSECTION",
                            "edges",
                            "i",
                            String.valueOf(edges[0] + 1),
                            "j",
                            String.valueOf(edges[1] + 1)),
                    a);
        }
        return error("SELF_INTERSECTION", variant("SELF_INTERSECTION", "named", "name", name), a);
    }

    /** Sizes inside the limits of the type; the ones a shape of this kind is built from must be present. */
    private void dimensionRules(LayoutAsset a, AssetType type, Geometry local, List<ValidationIssue> out) {
        switch (type.geometryKind()) {
            case "POINT", "RECT" -> {
                size(a, "Length", a.lengthM(), type.min().lengthM(), type.max().lengthM(), true, out);
                size(a, "Width", a.widthM(), type.min().widthM(), type.max().widthM(), true, out);
            }
            case "CIRCLE" ->
                size(a, "Radius", a.radiusM(), type.min().radiusM(), type.max().radiusM(), true, out);
            case "QUAY_SEGMENT" -> {
                BigDecimal length = a.lengthM() != null
                        ? a.lengthM()
                        : local == null ? null : BigDecimal.valueOf(local.getLength());
                size(a, "Length", length, type.min().lengthM(), type.max().lengthM(), true, out);
            }
            case "LINE" ->
                size(
                        a,
                        "Line width",
                        a.lineWidthM(),
                        type.min().widthM(),
                        type.max().widthM(),
                        false,
                        out);
            default -> {}
        }
        size(a, "Height", a.heightM(), type.min().heightM(), type.max().heightM(), false, out);
    }

    /**
     * Minimums of the typed details that the editor also enforces: a pad's bays, rows and pitches and its height, and a
     * berth's depth. Each is checked only when it is present; a missing value is the database's NOT NULL to refuse.
     */
    private void detailRules(LayoutAsset a, List<ValidationIssue> out) {
        AssetDetails d = a.details();
        if (d == null) {
            return;
        }
        if (d.storagePad() != null) {
            var pad = d.storagePad();
            atLeast(a, "Bays", BigDecimal.valueOf(pad.bays()), 1, out);
            atLeast(a, "Rows", BigDecimal.valueOf(pad.rows()), 1, out);
            atLeast(a, "Bay pitch", pad.bayPitchM(), MIN_PAD_PITCH_M, out);
            atLeast(a, "Row pitch", pad.rowPitchM(), MIN_PAD_PITCH_M, out);
            atLeast(a, "Max height", pad.maxHeightM(), 1, out);
        }
        if (d.berth() != null) {
            atLeast(a, "Depth", d.berth().depthM(), 1, out);
        }
    }

    private void atLeast(LayoutAsset a, String label, BigDecimal value, double min, List<ValidationIssue> out) {
        if (value != null && value.compareTo(BigDecimal.valueOf(min)) < 0) {
            out.add(error(
                    "DIM_OUT_OF_RANGE", variant("DIM_OUT_OF_RANGE", "min", "label", label, "limit", number(min)), a));
        }
    }

    private void size(
            LayoutAsset a,
            String label,
            BigDecimal value,
            BigDecimal min,
            BigDecimal max,
            boolean required,
            List<ValidationIssue> out) {
        if (value == null) {
            if (required && min != null) {
                out.add(error(
                        "DIM_OUT_OF_RANGE",
                        variant("DIM_OUT_OF_RANGE", "min", "label", label, "limit", number(min.doubleValue())),
                        a));
            }
            return;
        }
        if (value.signum() <= 0 || (min != null && value.compareTo(min) < 0)) {
            double limit = min == null ? 0 : min.doubleValue();
            out.add(error(
                    "DIM_OUT_OF_RANGE", variant("DIM_OUT_OF_RANGE", "min", "label", label, "limit", number(limit)), a));
        } else if (max != null && value.compareTo(max) > 0) {
            out.add(error(
                    "DIM_OUT_OF_RANGE",
                    variant("DIM_OUT_OF_RANGE", "max", "label", label, "limit", number(max.doubleValue())),
                    a));
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Placement rules
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The first placement rule the shape fails, in the fixed order. In the layout list, where one shape can meet
     * several others, the failing rule reports each shape it meets once ({@code reportedPairs} keeps a pair from
     * showing twice); for one shape only the first is returned.
     */
    private List<ValidationIssue> placementErrors(
            Subject s,
            List<Contact> contacts,
            List<LocalBerth> berths,
            Set<String> reportedPairs,
            Map<UUID, String> names) {
        boolean layoutList = reportedPairs != null;
        LayoutAsset a = s.asset();
        boolean mayBeOverWater = s.type().flags().mayExtendOverWater();

        if (!mayBeOverWater && quay != null && hasWaterVertex(s.local())) {
            return List.of(error("ON_WATER", message("ON_WATER"), a));
        }
        if (!mayBeOverWater && landLocal != null && !landLocal.covers(s.local())) {
            return List.of(error("OUTSIDE_LAND", message("OUTSIDE_LAND"), a));
        }

        var zoneHits = exclusionHits(s, contacts);
        if (!zoneHits.isEmpty()) {
            var issues = zoneHits.stream()
                    .map(c -> error(
                            "IN_EXCLUSION_ZONE", message("IN_EXCLUSION_ZONE", "zone", c.otherName()), a, c.otherId()))
                    .toList();
            return layoutList ? issues : issues.subList(0, 1);
        }

        List<ValidationIssue> meeting = new ArrayList<>();
        if ("QUAY_SEGMENT".equals(s.type().geometryKind())) {
            for (LocalBerth other : berths) {
                if (!Objects.equals(other.id(), s.id()) && alongQuay(s.local(), other) > BERTH_OVERLAP_M) {
                    meeting.add(pair(
                            s,
                            other.id(),
                            "BERTH_OVERLAP",
                            message("BERTH_OVERLAP", "other", other.name()),
                            reportedPairs));
                }
            }
        } else if (s.type().flags().mustBeOnBerth()) {
            if (berths.stream().noneMatch(b -> standsOn(s.local(), b))) {
                meeting.add(error("CRANE_OFF_BERTH", message("CRANE_OFF_BERTH"), a));
            }
        } else if (s.type().flags().solid()) {
            for (Contact c : contacts) {
                boolean solidPair = c.overlapM2() > OVERLAP_AREA_M2 && isSolid(c.otherTypeCode());
                if (solidPair) {
                    meeting.add(pair(
                            s, c.otherId(), "OVERLAP", message("OVERLAP", "other", nameOf(c, names)), reportedPairs));
                }
            }
        }
        meeting.removeIf(Objects::isNull);
        return layoutList || meeting.isEmpty() ? meeting : meeting.subList(0, 1);
    }

    /** An overlap issue, or null when the other shape already reported the same pair. */
    private ValidationIssue pair(Subject s, UUID other, String code, String text, Set<String> reportedPairs) {
        if (reportedPairs != null && s.id() != null) {
            String key = s.id().compareTo(other) < 0 ? s.id() + "|" + other : other + "|" + s.id();
            if (!reportedPairs.add(key + code)) {
                return null;
            }
        }
        return error(code, text, s.asset(), other);
    }

    /** Zones the shape is inside of and does not have a recorded override for, the biggest overlap first. */
    private List<Contact> exclusionHits(Subject s, List<Contact> contacts) {
        boolean exempt =
                s.type().flags().zone() || AREA_DECLARATIONS.contains(s.type().code());
        boolean overridden = s.asset().override() != null
                && s.asset().override().reason() != null
                && !s.asset().override().reason().isBlank();
        if (exempt || overridden) {
            return List.of();
        }
        boolean line = s.local() instanceof LineString;
        return contacts.stream()
                .filter(c -> PlacementFacts.EXCLUSION_ZONE.equals(c.otherTypeCode()))
                .filter(c -> !c.allowedTypes().contains(s.type().code()))
                .filter(c -> line ? c.crossing() : c.overlapM2() > OVERLAP_AREA_M2)
                .sorted(Comparator.comparingDouble(Contact::overlapM2).reversed())
                .toList();
    }

    private List<ValidationIssue> placementWarnings(Subject s, List<Contact> contacts, Map<UUID, String> names) {
        var out = new ArrayList<ValidationIssue>();
        LayoutAsset a = s.asset();
        if (PlacementFacts.BLOCK_TYPES.contains(s.type().code())) {
            for (Contact c : contacts) {
                boolean onRoad = PlacementFacts.ROAD_TYPES.contains(c.otherTypeCode())
                        && (c.overlapM2() > OVERLAP_AREA_M2 || (c.overlapM2() == 0 && c.crossing()));
                if (onRoad) {
                    out.add(new ValidationIssue(
                            "ON_ROAD",
                            IssueLevel.WARNING,
                            message("ON_ROAD", "block", s.name(), "road", nameOf(c, names)),
                            ids(a, c.otherId())));
                }
            }
        }
        AssetDetails.StoragePad pad = a.details() == null ? null : a.details().storagePad();
        if (pad != null
                && pad.bayPitchM() != null
                && pad.rowPitchM() != null
                && (pad.bayPitchM().doubleValue() < MIN_PILE_SLOT_M
                        || pad.rowPitchM().doubleValue() < MIN_PILE_SLOT_M)) {
            out.add(new ValidationIssue(
                    "PILE_SLOT_TOO_SMALL",
                    IssueLevel.WARNING,
                    message(
                            "PILE_SLOT_TOO_SMALL",
                            "block",
                            s.name(),
                            "bp",
                            number(pad.bayPitchM().doubleValue()),
                            "rp",
                            number(pad.rowPitchM().doubleValue())),
                    ids(a)));
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Quay geometry in the quay frame
    // ---------------------------------------------------------------------------------------------------------------

    private boolean hasWaterVertex(Geometry local) {
        for (Coordinate c : local.getCoordinates()) {
            if (quay.isWater(c)) {
                return true;
            }
        }
        return false;
    }

    private List<LocalBerth> localBerths(List<Berth> berths) {
        var out = new ArrayList<LocalBerth>(berths.size());
        for (Berth b : berths) {
            if (b.line() == null || b.line().isEmpty()) {
                continue;
            }
            Geometry line = Local.of(b.line(), site.frame());
            var box = line.getEnvelopeInternal();
            out.add(new LocalBerth(b.id(), b.name(), line, box.getMinX(), box.getMaxX()));
        }
        return out;
    }

    /** Length of quay two berths share, measured along the quay (local x). */
    private static double alongQuay(Geometry local, LocalBerth other) {
        var box = local.getEnvelopeInternal();
        return Math.min(box.getMaxX(), other.maxX()) - Math.max(box.getMinX(), other.minX());
    }

    /** A rail quay crane stands on a berth when its centre is along the berth and its footprint is near the berth line. */
    private static boolean standsOn(Geometry local, LocalBerth berth) {
        double x = local.getCentroid().getX();
        return x >= berth.minX() && x <= berth.maxX() && local.distance(berth.line()) <= RAIL_CRANE_MAX_OFFSET_M;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------------------------------------------------

    private boolean isSolid(String typeCode) {
        AssetType t = types.get(typeCode);
        return t != null && t.flags().solid();
    }

    private static boolean isLine(AssetType type) {
        return "LINE".equals(type.geometryKind()) || "QUAY_SEGMENT".equals(type.geometryKind());
    }

    private static String nameOf(Contact c, Map<UUID, String> names) {
        String known = names.get(c.otherId());
        return known != null ? known : c.otherName();
    }

    private static String displayName(LayoutAsset a) {
        if (a.name() != null && !a.name().isBlank()) {
            return a.name();
        }
        return a.code() != null ? a.code() : a.typeCode();
    }

    private static List<String> ids(LayoutAsset a, UUID... others) {
        var out = new ArrayList<String>();
        if (a.id() != null) {
            out.add(a.id().toString());
        }
        for (UUID other : others) {
            out.add(other.toString());
        }
        return out;
    }

    private static ValidationIssue error(String code, String text, LayoutAsset a, UUID... others) {
        return new ValidationIssue(code, IssueLevel.ERROR, text, ids(a, others));
    }

    /** Errors first, then warnings; each group keeps the order the rules ran in. */
    private static ValidationReport report(List<ValidationIssue> issues) {
        var sorted = new ArrayList<ValidationIssue>(issues.size());
        issues.stream().filter(i -> i.level() == IssueLevel.ERROR).forEach(sorted::add);
        issues.stream().filter(i -> i.level() == IssueLevel.WARNING).forEach(sorted::add);
        return new ValidationReport(sorted);
    }
}

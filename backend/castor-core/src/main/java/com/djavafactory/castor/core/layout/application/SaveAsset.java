package com.djavafactory.castor.core.layout.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.CoordinateSequenceFilter;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;

import com.djavafactory.castor.core.audit.application.AuditService;
import com.djavafactory.castor.core.audit.domain.AuditAction;
import com.djavafactory.castor.core.geometry.application.GeoJsonMapper;
import com.djavafactory.castor.core.geometry.domain.CoordinateRounding;
import com.djavafactory.castor.core.geometry.domain.LonLat;
import com.djavafactory.castor.core.geometry.domain.QuayFrame;
import com.djavafactory.castor.core.layout.domain.AssetCodes;
import com.djavafactory.castor.core.layout.domain.AssetDetails;
import com.djavafactory.castor.core.layout.domain.AssetType;
import com.djavafactory.castor.core.layout.domain.AssetTypeRepository;
import com.djavafactory.castor.core.layout.domain.DefaultDetails;
import com.djavafactory.castor.core.layout.domain.FeatureDraft;
import com.djavafactory.castor.core.layout.domain.Footprints;
import com.djavafactory.castor.core.layout.domain.LayoutAsset;
import com.djavafactory.castor.core.layout.domain.LayoutFeatureValidator;
import com.djavafactory.castor.core.layout.domain.LayoutRepository;
import com.djavafactory.castor.core.layout.domain.LayoutStatus;
import com.djavafactory.castor.core.layout.domain.LayoutVersion;
import com.djavafactory.castor.core.layout.domain.ValidationIssue;
import com.djavafactory.castor.core.port.domain.AccuracyClass;
import com.djavafactory.castor.core.port.domain.DataSourceInfo;
import com.djavafactory.castor.core.port.domain.Port;
import com.djavafactory.castor.core.port.domain.PortRepository;
import com.djavafactory.castor.core.shared.domain.ConflictException;
import com.djavafactory.castor.core.shared.domain.MissingIfMatchException;
import com.djavafactory.castor.core.shared.domain.ResourceNotFoundException;
import com.djavafactory.castor.core.shared.domain.RuleViolation;
import com.djavafactory.castor.core.shared.domain.RuleViolationException;

/**
 * Use cases of one asset in a draft: create (also re-create for undo), merge-patch, override, delete and duplicate.
 * Every change is checked with the DESIGNER-03 rules and the layout feature rules before anything is stored, runs in one
 * transaction with exactly one audit row, and canonicalises every coordinate to 7 decimals (CORE-01).
 */
@ApplicationScoped
public class SaveAsset {

    private static final Set<String> LINK_KINDS = Set.of("SERVES", "FEEDS", "BELONGS_TO", "CONNECTS_TO");
    private static final Set<String> IGNORED_PATCH_FIELDS = Set.of("id", "computed", "rowVersion", "override");
    private static final double DUPLICATE_OFFSET_M = 20;
    private static final int OVERRIDE_MIN = 10;
    private static final int OVERRIDE_MAX = 500;
    private static final Set<String> DOOR_SIDES = Set.of("NORTH", "EAST", "SOUTH", "WEST");

    /** The stored asset as read back, with the warnings and the largest coordinate rounding change. */
    public record Saved(LayoutAsset asset, List<ValidationIssue> warnings, double adjustmentMm) {}

    /** What a delete removed besides the asset: the codes of assets that linked to it. */
    public record Deleted(List<String> dependents) {}

    private record Prepared(LayoutAsset asset, double adjustmentMm) {}

    private final LayoutRepository layouts;
    private final AssetTypeRepository types;
    private final PortRepository ports;
    private final LayoutValidation validation;
    private final AuditService audit;
    private final GeoJsonMapper geo;
    private final ObjectMapper json;
    private final ObjectMapper lenient;

    public SaveAsset(
            LayoutRepository layouts,
            AssetTypeRepository types,
            PortRepository ports,
            LayoutValidation validation,
            AuditService audit,
            GeoJsonMapper geo,
            ObjectMapper json) {
        this.layouts = layouts;
        this.types = types;
        this.ports = ports;
        this.validation = validation;
        this.audit = audit;
        this.geo = geo;
        this.json = json;
        this.lenient = json.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Create
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Creates an asset. A body with an {@code id} re-creates an asset that has no revision in this draft (undo of a
     * delete) with the same id, code and type; any other existing id is a conflict.
     */
    @Transactional
    public Saved create(UUID layoutId, String actor, JsonNode body) {
        LayoutVersion draft = requireDraft(layoutId);
        Context ctx = context(draft);
        ObjectNode props = object(body);
        AssetType type = requireType(ctx, text(props, "type"));

        UUID id;
        String code;
        String givenId = text(props, "id");
        if (givenId != null) {
            id = parseId(givenId);
            if (layouts.findRowVersion(draft.id(), id).isPresent()) {
                throw new ConflictException("Asset " + id + " already exists in this draft");
            }
            var identity = layouts.findIdentity(draft.portCode(), id)
                    .orElseThrow(() -> new ConflictException("Asset " + id + " is not known, it cannot be re-created"));
            if (!identity.typeCode().equals(type.code())
                    || (text(props, "code") != null && !identity.code().equals(text(props, "code")))) {
                throw new ConflictException("Code and type of asset " + id + " cannot change");
            }
            code = identity.code();
        } else {
            id = UUID.randomUUID();
            String given = text(props, "code");
            List<String> used = layouts.findCodes(draft.portCode());
            if (given == null || given.isBlank()) {
                code = AssetCodes.next(type.code(), used);
            } else if (used.contains(given)) {
                throw new ConflictException("Code " + given + " is already used in this port");
            } else {
                code = given;
            }
        }

        props.put("code", code);
        props.put("type", type.code());
        Prepared prepared = prepare(ctx, draft, id, type, props, null);
        return store(draft, ctx, prepared, actor);
    }

    private Saved store(LayoutVersion draft, Context ctx, Prepared prepared, String actor) {
        LayoutAsset asset = prepared.asset();
        List<ValidationIssue> warnings = check(draft, ctx, asset);
        long rowVersion = stored(() -> layouts.insert(draft, asset, actor));
        Saved saved = saved(versioned(asset, rowVersion, asset.override()), warnings, prepared.adjustmentMm());
        audit.record(
                AuditAction.ASSET_CREATED, "ASSET", asset.id().toString(), draft.id(), null, snapshot(saved.asset()));
        return saved;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Update
    // ---------------------------------------------------------------------------------------------------------------

    /** Applies a JSON merge patch to an asset of the draft; the If-Match row version must be the stored one. */
    @Transactional
    public Saved patch(UUID layoutId, UUID assetId, String ifMatch, String actor, JsonNode body) {
        LayoutVersion draft = requireDraft(layoutId);
        long expected = rowVersionOf(ifMatch);
        LayoutAsset before = existing(draft, assetId);
        if (before.rowVersion() != expected) {
            throw stale();
        }
        Context ctx = context(draft);
        AssetType type = requireType(ctx, before.typeCode());

        ObjectNode patch = object(body);
        for (String immutable : List.of("code", "type")) {
            String sent = text(patch, immutable);
            String current = "code".equals(immutable) ? before.code() : before.typeCode();
            if (sent != null && !sent.equals(current)) {
                throw violation("IMMUTABLE_FIELD", "/" + immutable, "The " + immutable + " of an asset cannot change");
            }
        }
        ObjectNode merged = properties(before);
        // a new geometry replaces the old one, the anchor then follows it unless it is sent too
        if (patch.has("geometry") && !patch.has("anchor") && !derived(type)) {
            merged.remove("anchor");
        }
        merge(merged, patch);
        Prepared prepared = prepare(ctx, draft, assetId, type, merged, before);

        LayoutAsset asset = prepared.asset();
        List<ValidationIssue> warnings = check(draft, ctx, asset);
        long rowVersion = stored(() -> layouts.update(draft, asset, expected, actor));
        Saved saved = saved(versioned(asset, rowVersion, asset.override()), warnings, prepared.adjustmentMm());
        audit.record(
                AuditAction.ASSET_UPDATED,
                "ASSET",
                assetId.toString(),
                draft.id(),
                snapshot(before),
                snapshot(saved.asset()));
        return saved;
    }

    /** Records an exclusion-zone override (reason 10 to 500 characters) on an asset of the draft. */
    @Transactional
    public Saved override(UUID layoutId, UUID assetId, String ifMatch, String reason, String actor) {
        LayoutVersion draft = requireDraft(layoutId);
        long expected = rowVersionOf(ifMatch);
        LayoutAsset before = existing(draft, assetId);
        if (before.rowVersion() != expected) {
            throw stale();
        }
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.length() < OVERRIDE_MIN || trimmed.length() > OVERRIDE_MAX) {
            throw violation(
                    "OVERRIDE_REASON",
                    "/override/reason",
                    "The reason must be " + OVERRIDE_MIN + " to " + OVERRIDE_MAX + " characters");
        }
        var recorded = new LayoutAsset.Override(trimmed, actor, Instant.now());
        long rowVersion = layouts.override(draft, assetId, expected, recorded, actor);
        Saved saved = saved(versioned(before, rowVersion, recorded), List.of(), 0);
        audit.record(
                AuditAction.OVERRIDE_RECORDED,
                "ASSET",
                assetId.toString(),
                draft.id(),
                snapshot(before),
                snapshot(saved.asset()));
        return saved;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Delete and duplicate
    // ---------------------------------------------------------------------------------------------------------------

    /** Removes the revision of an asset from the draft (the identity stays) and every link that pointed to it. */
    @Transactional
    public Deleted delete(UUID layoutId, UUID assetId, String ifMatch, String actor) {
        LayoutVersion draft = requireDraft(layoutId);
        long expected = rowVersionOf(ifMatch);
        LayoutAsset before = existing(draft, assetId);
        if (before.rowVersion() != expected) {
            throw stale();
        }
        List<String> dependents = layouts.delete(draft, assetId, expected);
        audit.record(AuditAction.ASSET_DELETED, "ASSET", assetId.toString(), draft.id(), snapshot(before), null);
        return new Deleted(dependents);
    }

    /** Copies an asset 20 m along local x and y with the name "<name> copy" and a new code. Links are not copied. */
    @Transactional
    public Saved duplicate(UUID layoutId, UUID assetId, String actor) {
        LayoutVersion draft = requireDraft(layoutId);
        LayoutAsset original = existing(draft, assetId);
        Context ctx = context(draft);
        AssetType type = requireType(ctx, original.typeCode());

        QuayFrame frame = ctx.frame();
        var at = frame.toLocal(original.anchor());
        ObjectNode props = properties(original);
        props.put("name", original.name() + " copy");
        props.put("code", AssetCodes.next(type.code(), layouts.findCodes(draft.portCode())));
        props.putArray("links");
        props.putObject("provenance")
                .put("source", "OPERATOR")
                .put("accuracy", "APPROXIMATE")
                .put("note", "Duplicated in the layout designer");
        LonLat moved = frame.toLonLat(at.x() + DUPLICATE_OFFSET_M, at.y() + DUPLICATE_OFFSET_M);
        props.set("anchor", json.createArrayNode().add(moved.lon()).add(moved.lat()));
        if (!derived(type)) {
            Geometry shifted = translate(geo.read(props.get("geometry").toString()), frame);
            props.set("geometry", geo.node(shifted));
        }
        Prepared prepared = prepare(ctx, draft, UUID.randomUUID(), type, props, null);
        return store(draft, ctx, prepared, actor);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // From JSON to a checked asset
    // ---------------------------------------------------------------------------------------------------------------

    /** The context of one request: the draft's port frame and the rule inputs that do not change per asset. */
    private record Context(Port port, QuayFrame frame, Map<String, AssetType> types, Set<String> sources) {}

    private Context context(LayoutVersion draft) {
        Port port = ports.findByCode(draft.portCode())
                .orElseThrow(() -> new ResourceNotFoundException("No port " + draft.portCode()));
        var frame = new QuayFrame(port.origin().lon(), port.origin().lat(), port.frameRotDeg());
        Map<String, AssetType> byCode = types.findAll().stream().collect(Collectors.toMap(AssetType::code, t -> t));
        Set<String> sources =
                ports.findDataSources().stream().map(DataSourceInfo::code).collect(Collectors.toSet());
        return new Context(port, frame, byCode, sources);
    }

    private static boolean derived(AssetType type) {
        return Set.of("POINT", "RECT", "CIRCLE").contains(type.geometryKind());
    }

    /** Turns merged properties into the asset to store: sizes, geometry, anchor, details, links and provenance. */
    private Prepared prepare(
            Context ctx, LayoutVersion draft, UUID id, AssetType type, ObjectNode p, LayoutAsset before) {
        String name = text(p, "name");
        if (name == null || name.isBlank()) {
            throw violation("NAME_REQUIRED", "/properties/name", "Name is required");
        }
        BigDecimal length = decimal(p, "lengthM");
        BigDecimal width = decimal(p, "widthM");
        BigDecimal height = decimal(p, "heightM");
        BigDecimal radius = decimal(p, "radiusM");
        BigDecimal lineWidth = decimal(p, "lineWidthM");
        var defaults = type.defaults();
        switch (type.geometryKind()) {
            case "POINT", "RECT" -> {
                length = length != null ? length : defaults.lengthM();
                width = width != null ? width : defaults.widthM();
            }
            case "CIRCLE" -> radius = radius != null ? radius : defaults.radiusM();
            case "LINE" -> lineWidth = lineWidth != null ? lineWidth : defaults.lineWidthM();
            default -> {}
        }
        height = height != null ? height : (before == null ? defaults.heightM() : null);
        BigDecimal heading = heading(p);

        double adjustmentMm = 0;
        LonLat anchor = null;
        JsonNode anchorNode = p.get("anchor");
        if (anchorNode != null && !anchorNode.isNull()) {
            LonLat raw = lonLat(anchorNode);
            anchor = new LonLat(CoordinateRounding.round7(raw.lon()), CoordinateRounding.round7(raw.lat()));
            adjustmentMm = distanceMm(raw, anchor);
        }

        Geometry geom;
        if (derived(type)) {
            if (anchor == null) {
                throw violation("ANCHOR_REQUIRED", "/properties/anchor", "The anchor [lon, lat] is required");
            }
            var shape = new LayoutAsset(
                    id,
                    null,
                    type.code(),
                    name,
                    null,
                    anchor,
                    heading,
                    length,
                    width,
                    height,
                    radius,
                    lineWidth,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    AssetDetails.NONE,
                    List.of(),
                    0);
            try {
                geom = Footprints.derive(type, shape, ctx.frame());
            } catch (RuntimeException e) {
                if (e instanceof RuleViolationException) {
                    throw e;
                }
                throw violation("SIZE_REQUIRED", "/properties", "A size needed for " + type.code() + " is missing");
            }
        } else {
            JsonNode g = p.get("geometry");
            if (g == null || g.isNull()) {
                if (!"QUAY_SEGMENT".equals(type.geometryKind()) || anchor == null) {
                    throw violation("GEOMETRY_REQUIRED", "/geometry", type.code() + " needs a geometry");
                }
                geom = berthAlongQuay(ctx, anchor, length == null ? defaults.lengthM() : length);
            } else {
                Geometry raw;
                try {
                    raw = geo.read(g.toString());
                } catch (RuntimeException e) {
                    throw violation("GEOMETRY_INVALID", "/geometry", "The geometry is not a valid GeoJSON geometry");
                }
                double[] worst = {0};
                geom = canonical(raw, worst);
                adjustmentMm = Math.max(adjustmentMm, worst[0]);
            }
            if (anchor == null) {
                var pt = geom.getInteriorPoint();
                anchor = new LonLat(CoordinateRounding.round7(pt.getX()), CoordinateRounding.round7(pt.getY()));
            }
        }

        String sourceCode = "OPERATOR";
        String accuracy = "APPROXIMATE";
        LocalDate capturedOn = null;
        String note = null;
        JsonNode prov = p.get("provenance");
        if (prov != null && prov.isObject()) {
            sourceCode = textOr(prov, "source", sourceCode);
            accuracy = textOr(prov, "accuracy", accuracy);
            note = text(prov, "note");
            String captured = text(prov, "capturedOn");
            if (captured != null) {
                try {
                    capturedOn = LocalDate.parse(captured);
                } catch (DateTimeParseException e) {
                    throw violation(
                            "PROVENANCE_DATE", "/properties/provenance/capturedOn", "capturedOn must be a date");
                }
            }
        }
        if (!ctx.sources().contains(sourceCode)) {
            throw violation("UNKNOWN_SOURCE", "/properties/provenance/source", "Unknown data source " + sourceCode);
        }
        try {
            AccuracyClass.valueOf(accuracy);
        } catch (IllegalArgumentException e) {
            throw violation("UNKNOWN_ACCURACY", "/properties/provenance/accuracy", "Unknown accuracy " + accuracy);
        }

        AssetDetails details = details(p.get("details"));
        if (before == null) {
            details = DefaultDetails.withDefaults(details, DefaultDetails.of(type));
        }
        List<LayoutAsset.Link> links = links(draft, id, p.get("links"));

        var override = before == null ? null : before.override();
        Integer modelRev = before != null && before.modelRev() != null ? before.modelRev() : (Integer) type.modelRev();
        var asset = new LayoutAsset(
                id,
                text(p, "code"),
                type.code(),
                name.trim(),
                geom,
                anchor,
                heading,
                length,
                width,
                height,
                radius,
                lineWidth,
                decimalOr(p, "elevationM", BigDecimal.ZERO),
                decimalOr(p, "clearanceM", BigDecimal.ZERO),
                modelRev,
                text(p, "storageZoneCode"),
                text(p, "operatorName"),
                text(p, "description"),
                new LayoutAsset.Provenance(sourceCode, accuracy, capturedOn, note),
                override,
                details,
                links,
                before == null ? 0 : before.rowVersion());
        return new Prepared(asset, adjustmentMm);
    }

    /** The rules of DESIGNER-03 first (their messages are what the editor sees), then the feature rules. */
    private List<ValidationIssue> check(LayoutVersion draft, Context ctx, LayoutAsset asset) {
        List<ValidationIssue> warnings = validation.requireSavable(draft.id(), asset);
        var extent = geo.read(ctx.port().extent());
        var violations = new LayoutFeatureValidator(ctx.types(), ctx.frame(), extent)
                .validate(List.of(new FeatureDraft(asset, List.of())));
        // links are checked by id against the draft, not by code
        violations =
                violations.stream().filter(v -> !v.code().startsWith("LINK_")).toList();
        if (!violations.isEmpty()) {
            throw new RuleViolationException("The asset breaks layout rules", violations);
        }
        return warnings;
    }

    private AssetDetails details(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return AssetDetails.NONE;
        }
        AssetDetails d;
        try {
            d = lenient.treeToValue(node, AssetDetails.class);
        } catch (Exception e) {
            throw violation("DETAILS_INVALID", "/properties/details", "The details are not valid: " + e.getMessage());
        }
        return new AssetDetails(
                d.berth() == null
                        ? null
                        : new AssetDetails.Berth(
                                d.berth().depthM(),
                                d.berth().maxLoaM(),
                                nonNull(d.berth().cargoFamilies())),
                d.crane(),
                d.storage() == null
                        ? null
                        : new AssetDetails.Storage(
                                d.storage().capacityValue(),
                                d.storage().capacityUnit(),
                                d.storage().usableRatio(),
                                d.storage().designFill(),
                                d.storage().cargoTypeCode(),
                                nonNull(d.storage().allowedCargoTypes()),
                                d.storage().maxFloorLoadTM2(),
                                d.storage().maxStackM(),
                                d.storage().bearingCapacityKpa(),
                                d.storage().weatherExposed()),
                d.storagePad() == null
                        ? null
                        : new AssetDetails.StoragePad(
                                d.storagePad().bays(),
                                d.storagePad().rows(),
                                d.storagePad().bayPitchM(),
                                d.storagePad().rowPitchM(),
                                d.storagePad().maxHeightM()),
                d.containerYard(),
                warehouse(d.warehouse()),
                d.flow(),
                d.surface(),
                d.zone() == null ? null : new AssetDetails.Zone(nonNull(d.zone().allowedTypes())),
                d.mobile(),
                d.equipment());
    }

    /** A warehouse without a door side faces north; any other side than the four compass points is refused. */
    private static AssetDetails.Warehouse warehouse(AssetDetails.Warehouse w) {
        if (w == null) {
            return null;
        }
        String side = w.doorSide() == null || w.doorSide().isBlank() ? "NORTH" : w.doorSide();
        if (!DOOR_SIDES.contains(side)) {
            throw violation(
                    "DOOR_SIDE_INVALID",
                    "/properties/details/warehouse/doorSide",
                    "The door side must be NORTH, EAST, SOUTH or WEST");
        }
        return new AssetDetails.Warehouse(w.roofRiseM(), w.dockDoors(), side);
    }

    /**
     * Runs a layout write. A value the database refuses (a CHECK, NOT NULL or FOREIGN KEY violation, SQLSTATE class 23,
     * or a numeric overflow) is a rejected request, not a server fault: it becomes a 422 and the transaction rolls back.
     */
    private static <T> T stored(Supplier<T> write) {
        try {
            return write.get();
        } catch (RuntimeException e) {
            if (refusedByDatabase(e)) {
                throw violation(
                        "DETAILS_INVALID",
                        "/properties/details",
                        "The details are not valid: a value is outside the limits the layout accepts");
            }
            throw e;
        }
    }

    private static boolean refusedByDatabase(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException sql && sql.getSQLState() != null) {
                String state = sql.getSQLState();
                if (state.startsWith("23") || "22003".equals(state)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> nonNull(List<String> list) {
        return list == null ? List.of() : List.copyOf(list);
    }

    private List<LayoutAsset.Link> links(LayoutVersion draft, UUID self, JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw violation("LINK_TARGET", "/properties/links", "links must be a list");
        }
        var out = new ArrayList<LayoutAsset.Link>();
        for (JsonNode l : node) {
            String kind = text(l, "kind");
            String to = text(l, "toAssetId");
            if (kind == null || !LINK_KINDS.contains(kind)) {
                throw violation("LINK_KIND", "/properties/links", "Unknown link kind " + kind);
            }
            UUID target;
            try {
                target = UUID.fromString(to);
            } catch (RuntimeException e) {
                throw violation("LINK_TARGET", "/properties/links", "Link target " + to + " does not exist");
            }
            if (target.equals(self)) {
                throw violation("LINK_SELF", "/properties/links", "An asset cannot link to itself");
            }
            if (layouts.findRowVersion(draft.id(), target).isEmpty()) {
                throw violation("LINK_TARGET", "/properties/links", "Link target " + to + " does not exist");
            }
            out.add(new LayoutAsset.Link(kind, target));
        }
        return out;
    }

    /** A berth of the given length centred on the quay-line point nearest to the anchor, kept inside the line. */
    private Geometry berthAlongQuay(Context ctx, LonLat anchor, BigDecimal lengthM) {
        if (ctx.port().quayLine() == null || lengthM == null) {
            throw violation("GEOMETRY_REQUIRED", "/geometry", "QUAY_SEGMENT needs a geometry");
        }
        Geometry quay = geo.read(ctx.port().quayLine());
        if (!(quay instanceof LineString)) {
            throw violation("GEOMETRY_REQUIRED", "/geometry", "QUAY_SEGMENT needs a geometry");
        }
        Geometry local = quay.copy();
        mapCoordinates(local, (x, y) -> {
            var l = ctx.frame().toLocal(x, y);
            return new double[] {l.x(), l.y()};
        });
        var at = ctx.frame().toLocal(anchor);
        var line = new LengthIndexedLine(local);
        double centre = line.project(new Coordinate(at.x(), at.y()));
        double len = lengthM.doubleValue();
        double start = Math.max(line.getStartIndex(), Math.min(centre - len / 2, line.getEndIndex() - len));
        Geometry piece = line.extractLine(start, Math.min(start + len, line.getEndIndex()));
        double[] ignored = {0};
        mapCoordinates(piece, (x, y) -> {
            LonLat w = ctx.frame().toLonLat(x, y);
            return new double[] {w.lon(), w.lat()};
        });
        piece.setSRID(Footprints.SRID);
        return canonical(piece, ignored);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Geometry helpers
    // ---------------------------------------------------------------------------------------------------------------

    /** A copy with every coordinate rounded to 7 decimals; worst[0] receives the largest move in millimetres. */
    private static Geometry canonical(Geometry source, double[] worst) {
        Geometry copy = source.copy();
        copy.apply(new CoordinateSequenceFilter() {
            @Override
            public void filter(CoordinateSequence seq, int i) {
                double x = seq.getX(i);
                double y = seq.getY(i);
                double rx = CoordinateRounding.round7(x);
                double ry = CoordinateRounding.round7(y);
                worst[0] = Math.max(worst[0], distanceMm(new LonLat(x, y), new LonLat(rx, ry)));
                seq.setOrdinate(i, 0, rx);
                seq.setOrdinate(i, 1, ry);
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
        copy.setSRID(Footprints.SRID);
        return copy;
    }

    private Geometry translate(Geometry source, QuayFrame frame) {
        Geometry copy = source.copy();
        mapCoordinates(copy, (x, y) -> {
            var l = frame.toLocal(x, y);
            LonLat w = frame.toLonLat(l.x() + DUPLICATE_OFFSET_M, l.y() + DUPLICATE_OFFSET_M);
            return new double[] {w.lon(), w.lat()};
        });
        double[] ignored = {0};
        return canonical(copy, ignored);
    }

    private interface CoordinateMap {
        double[] apply(double x, double y);
    }

    private static void mapCoordinates(Geometry g, CoordinateMap f) {
        g.apply(new CoordinateSequenceFilter() {
            @Override
            public void filter(CoordinateSequence seq, int i) {
                double[] r = f.apply(seq.getX(i), seq.getY(i));
                seq.setOrdinate(i, 0, r[0]);
                seq.setOrdinate(i, 1, r[1]);
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
        g.geometryChanged();
    }

    private static double distanceMm(LonLat a, LonLat b) {
        double dx = (b.lon() - a.lon()) * QuayFrame.metresPerDegreeLon(a.lat());
        double dy = (b.lat() - a.lat()) * QuayFrame.metresPerDegreeLat(a.lat());
        return Math.hypot(dx, dy) * 1000;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Loading, snapshots and request rules
    // ---------------------------------------------------------------------------------------------------------------

    private LayoutVersion requireDraft(UUID layoutId) {
        LayoutVersion version = layouts.findVersion(layoutId)
                .orElseThrow(() -> new ResourceNotFoundException("No layout version " + layoutId));
        if (version.status() != LayoutStatus.DRAFT) {
            throw new ConflictException("Layout version is not a draft");
        }
        return version;
    }

    private LayoutAsset existing(LayoutVersion draft, UUID assetId) {
        return layouts.loadAsset(draft, assetId)
                .map(v -> v.assets().get(0))
                .orElseThrow(() -> new ResourceNotFoundException("Layout " + draft.id() + " has no asset " + assetId));
    }

    private static Saved saved(LayoutAsset asset, List<ValidationIssue> warnings, double adjustmentMm) {
        return new Saved(asset, warnings, Math.round(adjustmentMm * 1000) / 1000.0);
    }

    /** The asset as stored after a write: the same values with the new row version (and override). */
    private static LayoutAsset versioned(LayoutAsset a, long rowVersion, LayoutAsset.Override override) {
        return new LayoutAsset(
                a.id(),
                a.code(),
                a.typeCode(),
                a.name(),
                a.geom(),
                a.anchor(),
                a.headingDeg(),
                a.lengthM(),
                a.widthM(),
                a.heightM(),
                a.radiusM(),
                a.lineWidthM(),
                a.elevationM(),
                a.clearanceM(),
                a.modelRev(),
                a.storageZoneCode(),
                a.operatorName(),
                a.description(),
                a.provenance(),
                override,
                a.details(),
                a.links(),
                rowVersion);
    }

    private AssetType requireType(Context ctx, String code) {
        AssetType type = code == null ? null : ctx.types().get(code);
        if (type == null) {
            throw violation("UNKNOWN_TYPE", "/properties/type", "Unknown asset type " + code);
        }
        return type;
    }

    private static UUID parseId(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw violation("ID_INVALID", "/id", "The asset id is not a UUID");
        }
    }

    /** If-Match carries the row version, plain or as an entity tag ("3", W/"3"). */
    private static long rowVersionOf(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new MissingIfMatchException();
        }
        String v = ifMatch.trim();
        if (v.startsWith("W/")) {
            v = v.substring(2);
        }
        v = v.replace("\"", "");
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw stale();
        }
    }

    private static ConflictException stale() {
        return new ConflictException("The asset was changed by someone else, reload and try again");
    }

    private static RuleViolationException violation(String code, String pointer, String message) {
        return new RuleViolationException(message, List.of(new RuleViolation(code, null, pointer, message)));
    }

    private ObjectNode object(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw violation("BODY_INVALID", "/", "The request body must be a JSON object");
        }
        return (ObjectNode) body.deepCopy();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Properties <-> asset
    // ---------------------------------------------------------------------------------------------------------------

    /** The writable properties of a stored asset as the JSON a patch is merged into. */
    private ObjectNode properties(LayoutAsset a) {
        ObjectNode p = json.createObjectNode();
        p.put("code", a.code());
        p.put("type", a.typeCode());
        p.put("name", a.name());
        p.set("geometry", geo.node(a.geom()));
        p.set(
                "anchor",
                json.createArrayNode().add(a.anchor().lon()).add(a.anchor().lat()));
        number(p, "headingDeg", a.headingDeg());
        number(p, "lengthM", a.lengthM());
        number(p, "widthM", a.widthM());
        number(p, "heightM", a.heightM());
        number(p, "radiusM", a.radiusM());
        number(p, "lineWidthM", a.lineWidthM());
        number(p, "elevationM", a.elevationM());
        number(p, "clearanceM", a.clearanceM());
        p.put("storageZoneCode", a.storageZoneCode());
        p.put("operatorName", a.operatorName());
        p.put("description", a.description());
        p.set("details", json.valueToTree(a.details()));
        var links = p.putArray("links");
        a.links()
                .forEach(l -> links.addObject()
                        .put("kind", l.kind())
                        .put("toAssetId", l.toAssetId().toString()));
        p.putObject("provenance")
                .put("source", a.provenance().source())
                .put("accuracy", a.provenance().accuracy())
                .put(
                        "capturedOn",
                        a.provenance().capturedOn() == null
                                ? null
                                : a.provenance().capturedOn().toString())
                .put("note", a.provenance().note());
        return p;
    }

    /** The audit snapshot: the properties plus the identity and row version. */
    private ObjectNode snapshot(LayoutAsset a) {
        ObjectNode p = properties(a);
        p.put("id", a.id().toString());
        p.put("rowVersion", a.rowVersion());
        if (a.override() != null) {
            p.putObject("override")
                    .put("reason", a.override().reason())
                    .put("by", a.override().by());
        }
        return p;
    }

    /** JSON merge patch (RFC 7386): null removes, objects merge, anything else replaces. */
    private static void merge(ObjectNode target, JsonNode patch) {
        Iterator<Map.Entry<String, JsonNode>> it = patch.fields();
        while (it.hasNext()) {
            var e = it.next();
            if (IGNORED_PATCH_FIELDS.contains(e.getKey())) {
                continue;
            }
            JsonNode value = e.getValue();
            if (value.isNull()) {
                target.remove(e.getKey());
            } else if (value.isObject() && target.get(e.getKey()) instanceof ObjectNode child) {
                merge(child, value);
            } else {
                target.set(e.getKey(), value.deepCopy());
            }
        }
    }

    private static void number(ObjectNode node, String field, BigDecimal value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asText();
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        String v = text(node, field);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isNumber()) {
            throw violation("NUMBER_INVALID", "/properties/" + field, field + " must be a number");
        }
        return n.decimalValue();
    }

    private static BigDecimal decimalOr(JsonNode node, String field, BigDecimal fallback) {
        BigDecimal v = decimal(node, field);
        return v == null ? fallback : v;
    }

    /** Heading in degrees from true north, folded into 0 up to 360 and kept to the 3 decimals of the column. */
    private static BigDecimal heading(JsonNode p) {
        BigDecimal h = decimal(p, "headingDeg");
        if (h == null) {
            return null;
        }
        BigDecimal folded = h.remainder(BigDecimal.valueOf(360));
        if (folded.signum() < 0) {
            folded = folded.add(BigDecimal.valueOf(360));
        }
        folded = folded.setScale(3, RoundingMode.HALF_UP);
        return folded.compareTo(BigDecimal.valueOf(360)) >= 0 ? BigDecimal.ZERO.setScale(3) : folded;
    }

    private static LonLat lonLat(JsonNode node) {
        if (!node.isArray()
                || node.size() != 2
                || !node.get(0).isNumber()
                || !node.get(1).isNumber()) {
            throw violation("ANCHOR_INVALID", "/properties/anchor", "The anchor must be [lon, lat]");
        }
        double lon = node.get(0).asDouble();
        double lat = node.get(1).asDouble();
        if (lon < -180 || lon > 180 || lat < -90 || lat > 90) {
            throw violation("ANCHOR_INVALID", "/properties/anchor", "The anchor is not a valid longitude and latitude");
        }
        return new LonLat(lon, lat);
    }
}

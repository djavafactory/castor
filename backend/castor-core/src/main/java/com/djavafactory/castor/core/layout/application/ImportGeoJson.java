package com.djavafactory.castor.core.layout.application;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.SystemException;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;

import com.djavafactory.castor.core.audit.application.AuditService;
import com.djavafactory.castor.core.audit.domain.AuditAction;
import com.djavafactory.castor.core.geometry.application.GeoJsonMapper;
import com.djavafactory.castor.core.geometry.domain.QuayFrame;
import com.djavafactory.castor.core.layout.domain.AssetType;
import com.djavafactory.castor.core.layout.domain.AssetTypeRepository;
import com.djavafactory.castor.core.layout.domain.LayoutAsset;
import com.djavafactory.castor.core.layout.domain.LayoutRepository;
import com.djavafactory.castor.core.layout.domain.LayoutStatus;
import com.djavafactory.castor.core.layout.domain.LayoutVersion;
import com.djavafactory.castor.core.port.domain.DataSourceInfo;
import com.djavafactory.castor.core.port.domain.Port;
import com.djavafactory.castor.core.port.domain.PortRepository;
import com.djavafactory.castor.core.shared.application.OperationMetrics;
import com.djavafactory.castor.core.shared.domain.ConflictException;
import com.djavafactory.castor.core.shared.domain.InvalidRequestException;
import com.djavafactory.castor.core.shared.domain.ResourceNotFoundException;
import com.djavafactory.castor.core.shared.domain.RuleViolationException;

/**
 * Brings the shapes of a GeoJSON file into the draft (DESIGNER-10). Every feature goes through the same save as a single
 * asset (DESIGNER-04 and DESIGNER-03 rules), one after the other in one transaction, so a feature is judged against the
 * draft and against the features accepted before it in the same file. A dry run does exactly the same and then rolls the
 * transaction back, so its report is what the real import would give.
 */
@ApplicationScoped
public class ImportGeoJson {

    static final String DEFAULT_SOURCE = "SURVEY";
    static final String DEFAULT_ACCURACY = "VERIFIED";
    private static final String UNTYPED = "Skipped a ";
    static final String DEFAULT_NOTE = "Imported from GeoJSON";
    /** A given anchor is kept while it still agrees with the shape; further away than this the shape wins. */
    private static final double ANCHOR_AGREES_M = 0.5;

    /** How well the coordinates of the accepted features survived: the count compared and the largest move. */
    public record RoundTrip(int coordinates, double maxDifferenceMm) {}

    public record Report(
            String file, int added, int updated, int skipped, List<String> skipReasons, RoundTrip roundTrip) {}

    private final LayoutRepository layouts;
    private final AssetTypeRepository types;
    private final PortRepository ports;
    private final SaveAsset saveAsset;
    private final AuditService audit;
    private final GeoJsonMapper geo;
    private final ObjectMapper json;
    private final TransactionManager transactions;
    private final OperationMetrics metrics;

    public ImportGeoJson(
            LayoutRepository layouts,
            AssetTypeRepository types,
            PortRepository ports,
            SaveAsset saveAsset,
            AuditService audit,
            GeoJsonMapper geo,
            ObjectMapper json,
            TransactionManager transactions,
            OperationMetrics metrics) {
        this.layouts = layouts;
        this.types = types;
        this.ports = ports;
        this.saveAsset = saveAsset;
        this.audit = audit;
        this.geo = geo;
        this.json = json;
        this.transactions = transactions;
        this.metrics = metrics;
    }

    /** The state of one import run. */
    private static final class Run {
        final LayoutVersion draft;
        final Port port;
        final String actor;
        final Map<String, AssetType> types;
        final Map<String, DataSourceInfo> sources;
        final Map<String, LayoutAsset> byId = new HashMap<>();
        final Map<String, LayoutAsset> byCode = new HashMap<>();
        final Set<String> usedCodes = new HashSet<>();
        /** Id a feature had in the file to the id of the asset it became, to carry links over. */
        final Map<String, UUID> fileIds = new HashMap<>();

        final List<String> skipReasons = new ArrayList<>();
        int added;
        int updated;
        int coordinates;
        double maxDifferenceMm;

        Run(
                LayoutVersion draft,
                Port port,
                String actor,
                Map<String, AssetType> types,
                Map<String, DataSourceInfo> sources) {
            this.draft = draft;
            this.port = port;
            this.actor = actor;
            this.types = types;
            this.sources = sources;
        }

        void remember(LayoutAsset asset) {
            byId.put(asset.id().toString(), asset);
            byCode.put(asset.code(), asset);
        }
    }

    /** A feature that cannot be stored; the reason is what the report lists after the name. */
    private static final class Skip extends RuntimeException {
        Skip(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * @param dryRun true to check only: nothing is stored, no audit row is written
     * @throws InvalidRequestException when the file is not JSON or not a Feature or FeatureCollection
     */
    @Transactional
    public Report execute(UUID layoutId, String fileName, byte[] content, boolean dryRun, String actor) {
        JsonNode root = parse(content);
        List<JsonNode> features = featuresOf(root);

        LayoutVersion draft = layouts.findVersion(layoutId)
                .orElseThrow(() -> new ResourceNotFoundException("No layout version " + layoutId));
        if (draft.status() != LayoutStatus.DRAFT) {
            throw new ConflictException("Layout version is not a draft");
        }
        Port port = ports.findByCode(draft.portCode())
                .orElseThrow(() -> new ResourceNotFoundException("No port " + draft.portCode()));
        Map<String, AssetType> byType = types.findAll().stream().collect(Collectors.toMap(AssetType::code, t -> t));
        Map<String, DataSourceInfo> sources =
                ports.findDataSources().stream().collect(Collectors.toMap(DataSourceInfo::code, s -> s));

        var run = new Run(draft, port, actor, byType, sources);
        layouts.load(draft).assets().forEach(run::remember);
        run.usedCodes.addAll(layouts.findCodes(draft.portCode()));

        for (JsonNode feature : linkOrder(features)) {
            String name = nameOf(feature);
            try {
                store(run, feature, name);
            } catch (Skip e) {
                run.skipReasons.add(e.getMessage().startsWith(UNTYPED) ? e.getMessage() : name + ": " + e.getMessage());
            } catch (RuleViolationException e) {
                run.skipReasons.add(name + ": " + reasons(e));
            } catch (ConflictException e) {
                run.skipReasons.add(name + ": " + e.getMessage());
            }
        }

        var report = new Report(
                fileName,
                run.added,
                run.updated,
                run.skipReasons.size(),
                List.copyOf(run.skipReasons),
                new RoundTrip(run.coordinates, Math.round(run.maxDifferenceMm * 1000) / 1000.0));
        if (dryRun) {
            rollBack();
        } else {
            ObjectNode after = json.createObjectNode()
                    .put("file", fileName)
                    .put("added", report.added())
                    .put("updated", report.updated())
                    .put("skipped", report.skipped());
            audit.record(AuditAction.LAYOUT_IMPORTED, "LAYOUT", draft.id().toString(), draft.id(), null, after);
            metrics.importFinished();
        }
        return report;
    }

    private void rollBack() {
        try {
            transactions.setRollbackOnly();
        } catch (SystemException e) {
            throw new IllegalStateException("Could not roll back the dry run", e);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // File
    // ---------------------------------------------------------------------------------------------------------------

    private JsonNode parse(byte[] content) {
        try {
            return json.readTree(content);
        } catch (IOException e) {
            throw new InvalidRequestException("That file is not valid JSON");
        }
    }

    private static List<JsonNode> featuresOf(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new InvalidRequestException("That file is not a GeoJSON FeatureCollection or Feature");
        }
        String type = root.path("type").asText("");
        if ("Feature".equals(type)) {
            return List.of(root);
        }
        if ("FeatureCollection".equals(type) && root.path("features").isArray()) {
            var out = new ArrayList<JsonNode>();
            root.get("features").forEach(out::add);
            return out;
        }
        throw new InvalidRequestException("That file is not a GeoJSON FeatureCollection or Feature");
    }

    /** Features that others link to come first, so the links can be stored with the asset. Otherwise file order. */
    private static List<JsonNode> linkOrder(List<JsonNode> features) {
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode f : features) {
            String id = idOf(f);
            if (id != null) {
                byId.putIfAbsent(id, f);
            }
        }
        var ordered = new ArrayList<JsonNode>();
        var seen = new HashSet<JsonNode>();
        for (JsonNode f : features) {
            visit(f, byId, seen, ordered);
        }
        return ordered;
    }

    private static void visit(JsonNode f, Map<String, JsonNode> byId, Set<JsonNode> seen, List<JsonNode> out) {
        if (!seen.add(f)) {
            return;
        }
        JsonNode links = f.path("properties").path("links");
        if (links.isArray()) {
            for (JsonNode l : links) {
                JsonNode target = byId.get(l.path("toAssetId").asText(""));
                if (target != null) {
                    visit(target, byId, seen, out);
                }
            }
        }
        out.add(f);
    }

    private static String idOf(JsonNode feature) {
        JsonNode id = feature.get("id");
        return id == null || id.isNull() || !(id.isTextual() || id.isNumber()) ? null : id.asText();
    }

    private static String nameOf(JsonNode feature) {
        JsonNode props = feature.path("properties");
        for (String field : new String[] {"name", "code"}) {
            String v = props.path(field).asText("").trim();
            if (!v.isEmpty()) {
                return v;
            }
        }
        String id = idOf(feature);
        return id != null ? id : "A feature";
    }

    private static String reasons(RuleViolationException e) {
        var messages = e.violations().stream().map(v -> v.message()).distinct().toList();
        return messages.isEmpty() ? e.getMessage() : String.join("; ", messages);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // One feature
    // ---------------------------------------------------------------------------------------------------------------

    private void store(Run run, JsonNode feature, String name) {
        JsonNode props = feature.path("properties").isObject() ? feature.get("properties") : json.createObjectNode();
        JsonNode geometry = feature.get("geometry");
        if (geometry == null
                || !geometry.isObject()
                || !geometry.path("coordinates").isArray()) {
            throw new Skip("The feature has no geometry");
        }
        String kind = geometry.path("type").asText("");
        if (!Set.of("Point", "LineString", "Polygon").contains(kind)) {
            throw new Skip("A " + (kind.isEmpty() ? "shape" : kind) + " cannot be imported");
        }

        String fileId = idOf(feature);
        LayoutAsset existing = null;
        if (fileId != null) {
            existing = run.byId.containsKey(fileId) ? run.byId.get(fileId) : run.byCode.get(fileId);
        }
        AssetType type = typeOf(run, props, kind, existing);

        ObjectNode body = json.createObjectNode();
        body.put("name", name(props, existing, type));
        Geometry input = shape(body, type, kind, stripZ(geometry), props);
        provenance(run, body, props);
        copy(body, props);
        if (props.path("links").isArray()) {
            body.set("links", links(run, props.get("links"), existing));
        }

        LayoutAsset stored;
        boolean update = existing != null;
        if (update) {
            if (!existing.typeCode().equals(type.code())) {
                throw new Skip("The type of an asset cannot change");
            }
            stored = saveAsset
                    .patchForImport(
                            run.draft.id(), existing.id(), Long.toString(existing.rowVersion()), run.actor, body)
                    .asset();
        } else {
            newIdentity(run, body, fileId, props, type);
            stored = saveAsset.createForImport(run.draft.id(), run.actor, body).asset();
        }

        run.remember(stored);
        run.usedCodes.add(stored.code());
        if (fileId != null) {
            run.fileIds.put(fileId, stored.id());
        }
        if (update) {
            run.updated++;
        } else {
            run.added++;
        }
        compare(run, input, stored);
    }

    /** properties.type or castorType when it is a known type; else a LineString is a FENCE and a Polygon a LAYDOWN_YARD. */
    private AssetType typeOf(Run run, JsonNode props, String geometryKind, LayoutAsset existing) {
        for (String field : new String[] {"type", "castorType"}) {
            AssetType known = run.types.get(props.path(field).asText(""));
            if (known != null) {
                return known;
            }
        }
        if (existing != null) {
            return run.types.get(existing.typeCode());
        }
        AssetType fallback = switch (geometryKind) {
            case "LineString" -> run.types.get("FENCE");
            case "Polygon" -> run.types.get("LAYDOWN_YARD");
            default -> null;
        };
        if (fallback == null) {
            throw new Skip(UNTYPED + geometryKind + " without a Castor type");
        }
        return fallback;
    }

    private static String name(JsonNode props, LayoutAsset existing, AssetType type) {
        String given = props.path("name").asText("").trim();
        if (!given.isEmpty()) {
            return given;
        }
        if (existing != null) {
            return existing.name();
        }
        String code = props.path("code").asText("").trim();
        return code.isEmpty() ? type.label() : code;
    }

    /**
     * Puts geometry, anchor, heading and sizes into the body as the type needs them and returns the geometry as the file
     * had it, for the round-trip comparison. Points, rectangles and circles are placed by an anchor and rebuilt from
     * their size; a polygon given for one of them is placed at its centroid.
     */
    private Geometry shape(ObjectNode body, AssetType type, String kind, JsonNode geometry, JsonNode props) {
        Geometry input;
        try {
            input = geo.read(geometry.toString());
        } catch (RuntimeException e) {
            throw new Skip("The geometry is not valid GeoJSON");
        }
        JsonNode anchor = props.get("anchor");
        switch (type.geometryKind()) {
            case "POINT", "RECT", "CIRCLE" -> {
                if ("LineString".equals(kind)) {
                    throw new Skip(type.code() + " needs a point or a polygon, not a LineString");
                }
                Coordinate at = "Point".equals(kind)
                        ? input.getCoordinate()
                        : input.getCentroid().getCoordinate();
                body.set("anchor", agreeingAnchor(anchor, at));
                number(body, "headingDeg", props, "heading_deg", "headingDeg");
            }
            case "LINE" -> {
                if (!"LineString".equals(kind)) {
                    throw new Skip(type.code() + " needs a LineString, not a " + kind);
                }
                body.set("geometry", geometry);
            }
            case "POLYGON" -> {
                if (!"Polygon".equals(kind)) {
                    throw new Skip(type.code() + " needs a Polygon, not a " + kind);
                }
                body.set("geometry", geometry);
            }
            default -> {
                // QUAY_SEGMENT: a line along the quay, or a point to place a berth of the given length at
                if ("Point".equals(kind)) {
                    body.set("anchor", point(input.getCoordinate()));
                } else if ("LineString".equals(kind)) {
                    body.set("geometry", geometry);
                } else {
                    throw new Skip(type.code() + " needs a LineString or a point, not a " + kind);
                }
            }
        }
        if (body.has("geometry") && anchor != null && anchorInside(anchor, input)) {
            body.set("anchor", anchor);
        }
        number(body, "lengthM", props, "length_m", "lengthM");
        number(body, "widthM", props, "width_m", "widthM");
        number(body, "heightM", props, "height_m", "heightM");
        number(body, "radiusM", props, "radius_m", "radiusM");
        number(body, "lineWidthM", props, "line_width_m", "lineWidthM");
        return input;
    }

    /** The given anchor when it is within half a metre of where the shape puts it, else the shape's own position. */
    private JsonNode agreeingAnchor(JsonNode given, Coordinate shape) {
        if (given != null
                && given.isArray()
                && given.size() == 2
                && given.get(0).isNumber()
                && given.get(1).isNumber()) {
            var g = new Coordinate(given.get(0).asDouble(), given.get(1).asDouble());
            if (distanceMm(g, shape) <= ANCHOR_AGREES_M * 1000) {
                return given;
            }
        }
        return point(shape);
    }

    private static boolean anchorInside(JsonNode anchor, Geometry shape) {
        if (!anchor.isArray()
                || anchor.size() != 2
                || !anchor.get(0).isNumber()
                || !anchor.get(1).isNumber()) {
            return false;
        }
        var factory = shape.getFactory();
        return shape.covers(factory.createPoint(
                new Coordinate(anchor.get(0).asDouble(), anchor.get(1).asDouble())));
    }

    private ArrayNode point(Coordinate c) {
        return json.createArrayNode().add(c.x).add(c.y);
    }

    /** Provenance from the file, else the import default; data that may not be stored is refused. */
    private void provenance(Run run, ObjectNode body, JsonNode props) {
        JsonNode given = props.path("provenance");
        String source = text(given, "source", DEFAULT_SOURCE);
        DataSourceInfo info = run.sources.get(source);
        if (info == null) {
            throw new Skip("Unknown data source " + source);
        }
        if (!info.mayPersist()) {
            throw new Skip(info.name() + " data cannot be stored");
        }
        ObjectNode p = body.putObject("provenance");
        p.put("source", source);
        p.put("accuracy", text(given, "accuracy", DEFAULT_ACCURACY));
        p.put(
                "capturedOn",
                text(
                        given,
                        "capturedOn",
                        LocalDate.now(ZoneId.of(run.port.timeZone())).toString()));
        p.put("note", text(given, "note", DEFAULT_NOTE));
    }

    private static String text(JsonNode node, String field, String fallback) {
        String v = node.path(field).isTextual() ? node.get(field).asText().trim() : "";
        return v.isEmpty() ? fallback : v;
    }

    /** First of the given names that holds a value, written under {@code field}. A non-number is passed on to be refused. */
    private static void number(ObjectNode body, String field, JsonNode props, String... names) {
        for (String n : names) {
            JsonNode v = props.get(n);
            if (v != null && !v.isNull()) {
                body.set(field, v);
                return;
            }
        }
    }

    /** The plain catalogue properties Castor itself writes on export. */
    private static void copy(ObjectNode body, JsonNode props) {
        for (String field :
                new String[] {"elevationM", "clearanceM", "storageZoneCode", "operatorName", "description", "details"
                }) {
            JsonNode v = props.get(field);
            if (v != null && !v.isNull()) {
                body.set(field, v);
            }
        }
    }

    /** Links by the ids of the file mapped to the assets they became; a link to an unknown asset is dropped. */
    private ArrayNode links(Run run, JsonNode links, LayoutAsset existing) {
        ArrayNode out = json.createArrayNode();
        for (JsonNode l : links) {
            String target = l.path("toAssetId").asText("");
            UUID id = run.fileIds.get(target);
            if (id == null && run.byId.containsKey(target)) {
                id = run.byId.get(target).id();
            }
            if (id != null && (existing == null || !id.equals(existing.id()))) {
                out.addObject().put("kind", l.path("kind").asText("")).put("toAssetId", id.toString());
            }
        }
        return out;
    }

    /**
     * A new asset keeps the id (and code) of a known asset of the port that has no revision in this draft, for example
     * after a blank draft was started; otherwise it gets a new id and the given code when it is free.
     */
    private void newIdentity(Run run, ObjectNode body, String fileId, JsonNode props, AssetType type) {
        body.put("type", type.code());
        if (fileId != null) {
            UUID known = uuid(fileId);
            if (known != null && layouts.findRowVersion(run.draft.id(), known).isEmpty()) {
                var identity = layouts.findIdentity(run.draft.portCode(), known);
                if (identity.isPresent() && identity.get().typeCode().equals(type.code())) {
                    body.put("id", known.toString());
                    body.put("code", identity.get().code());
                    return;
                }
            }
        }
        String code = props.path("code").asText("").trim();
        if (!code.isEmpty() && !run.usedCodes.contains(code)) {
            body.put("code", code);
        }
    }

    private static UUID uuid(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Round trip
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Compares the coordinates of the file with those stored (after the 7 decimals of CORE-01 and, for points, boxes and
     * circles, the footprint rebuilt from the anchor). Equal vertex counts are compared vertex by vertex; otherwise the
     * centroids are.
     */
    private void compare(Run run, Geometry input, LayoutAsset stored) {
        Coordinate[] in = input.getCoordinates();
        Coordinate[] out = stored.geom().getCoordinates();
        if ("Point".equals(input.getGeometryType())) {
            in = new Coordinate[] {input.getCoordinate()};
            out = new Coordinate[] {
                new Coordinate(stored.anchor().lon(), stored.anchor().lat())
            };
        }
        if (in.length != out.length) {
            in = new Coordinate[] {input.getCentroid().getCoordinate()};
            out = new Coordinate[] {stored.geom().getCentroid().getCoordinate()};
        }
        for (int i = 0; i < in.length; i++) {
            run.maxDifferenceMm = Math.max(run.maxDifferenceMm, distanceMm(in[i], out[i]));
        }
        run.coordinates += in.length;
    }

    private static double distanceMm(Coordinate a, Coordinate b) {
        double dx = (b.x - a.x) * QuayFrame.metresPerDegreeLon(a.y);
        double dy = (b.y - a.y) * QuayFrame.metresPerDegreeLat(a.y);
        return Math.hypot(dx, dy) * 1000;
    }

    /** A copy of the geometry with at most [lon, lat] per position: heights are not kept. */
    private JsonNode stripZ(JsonNode geometry) {
        ObjectNode copy = json.createObjectNode();
        copy.put("type", geometry.path("type").asText());
        copy.set("coordinates", strip(geometry.get("coordinates")));
        return copy;
    }

    private JsonNode strip(JsonNode node) {
        if (node.isArray() && node.size() > 0 && node.get(0).isNumber()) {
            return node.size() <= 2
                    ? node
                    : json.createArrayNode().add(node.get(0)).add(node.get(1));
        }
        ArrayNode out = json.createArrayNode();
        node.forEach(child -> out.add(strip(child)));
        return out;
    }
}

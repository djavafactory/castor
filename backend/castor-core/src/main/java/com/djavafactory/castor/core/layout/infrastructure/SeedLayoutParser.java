package com.djavafactory.castor.core.layout.infrastructure;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.djavafactory.castor.core.geometry.application.GeoJsonMapper;
import com.djavafactory.castor.core.geometry.domain.LonLat;
import com.djavafactory.castor.core.layout.domain.AssetDetails;
import com.djavafactory.castor.core.layout.domain.FeatureDraft;
import com.djavafactory.castor.core.layout.domain.LayoutAsset;
import com.djavafactory.castor.core.shared.domain.RuleViolation;
import com.djavafactory.castor.core.shared.domain.RuleViolationException;

/**
 * Reads a layout GeoJSON file (the seed format of CORE-04 part 6: one Feature per asset, id = code) into drafts. It
 * only translates; every rule is checked afterwards by the domain validator.
 */
final class SeedLayoutParser {

    /** The header of the file: {@code castor} member of the FeatureCollection. */
    record Header(int schemaVersion, String port, int versionNo, String status, String createdBy, String note) {}

    record Parsed(Header header, List<FeatureDraft> features) {}

    private final ObjectMapper json;
    private final GeoJsonMapper geo;

    SeedLayoutParser(ObjectMapper json, GeoJsonMapper geo) {
        this.json = json;
        this.geo = geo;
    }

    Parsed parse(byte[] file) throws IOException {
        JsonNode root = json.readTree(file);
        JsonNode h = root.path("castor");
        if (!"FeatureCollection".equals(root.path("type").asText()) || h.isMissingNode()) {
            throw bad("/", "A layout file is a FeatureCollection with a castor header");
        }
        var header = new Header(
                h.path("schemaVersion").asInt(),
                h.path("port").asText(),
                h.path("versionNo").asInt(),
                h.path("status").asText(),
                h.path("createdBy").asText(),
                h.path("note").isNull() ? null : h.path("note").asText());
        if (header.schemaVersion() != 1) {
            throw bad("/castor/schemaVersion", "Unsupported schemaVersion " + header.schemaVersion());
        }
        List<FeatureDraft> drafts = new ArrayList<>();
        for (JsonNode f : root.path("features")) {
            drafts.add(feature(f));
        }
        return new Parsed(header, drafts);
    }

    private FeatureDraft feature(JsonNode f) {
        JsonNode p = f.path("properties");
        String code = p.path("code").asText(f.path("id").asText(null));
        var anchor = p.path("anchor");
        var prov = p.path("provenance");
        var asset = new LayoutAsset(
                null,
                code,
                p.path("type").asText(null),
                p.path("name").asText(null),
                geo.read(f.path("geometry").toString()),
                new LonLat(anchor.path(0).asDouble(), anchor.path(1).asDouble()),
                dec(p, "headingDeg"),
                dec(p, "lengthM"),
                dec(p, "widthM"),
                dec(p, "heightM"),
                dec(p, "radiusM"),
                dec(p, "lineWidthM"),
                p.hasNonNull("elevationM") ? dec(p, "elevationM") : BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                p.path("storageZoneCode").asText(null),
                null,
                null,
                new LayoutAsset.Provenance(
                        prov.path("source").asText(null),
                        prov.path("accuracy").asText(null),
                        prov.hasNonNull("capturedOn")
                                ? LocalDate.parse(prov.path("capturedOn").asText())
                                : null,
                        prov.path("note").asText(null)),
                null,
                details(p.path("details")),
                List.of(),
                0);
        List<FeatureDraft.DraftLink> links = new ArrayList<>();
        for (JsonNode l : p.path("links")) {
            links.add(new FeatureDraft.DraftLink(
                    l.path("kind").asText(), l.path("to").asText()));
        }
        return new FeatureDraft(asset, links);
    }

    private AssetDetails details(JsonNode d) {
        AssetDetails.Berth berth = null;
        if (d.has("berth")) {
            JsonNode n = d.get("berth");
            berth = new AssetDetails.Berth(dec(n, "depthM"), dec(n, "maxLoaM"), strings(n.path("cargoFamilies")));
        }
        AssetDetails.Crane crane = null;
        if (d.has("crane")) {
            JsonNode n = d.get("crane");
            crane = new AssetDetails.Crane(
                    dec(n, "gaugeM"),
                    dec(n, "outreachM"),
                    dec(n, "backreachM"),
                    dec(n, "reachM"),
                    dec(n, "safeWorkingLoadT"));
        }
        AssetDetails.Storage storage = null;
        if (d.has("storage")) {
            JsonNode n = d.get("storage");
            storage = new AssetDetails.Storage(
                    dec(n, "capacityValue"),
                    n.path("capacityUnit").asText(),
                    n.hasNonNull("usableRatio") ? dec(n, "usableRatio") : BigDecimal.ONE,
                    n.hasNonNull("designFill") ? dec(n, "designFill") : BigDecimal.ZERO,
                    n.path("cargoTypeCode").asText(null),
                    strings(n.path("allowedCargoTypes")),
                    dec(n, "maxFloorLoadTM2"),
                    dec(n, "maxStackM"),
                    dec(n, "bearingCapacityKpa"),
                    !n.has("weatherExposed") || n.path("weatherExposed").asBoolean());
        }
        AssetDetails.StoragePad pad = null;
        if (d.has("storagePad")) {
            JsonNode n = d.get("storagePad");
            pad = new AssetDetails.StoragePad(
                    n.path("bays").asInt(),
                    n.path("rows").asInt(),
                    dec(n, "bayPitchM"),
                    dec(n, "rowPitchM"),
                    dec(n, "maxHeightM"));
        }
        AssetDetails.ContainerYard yard = null;
        if (d.has("containerYard")) {
            JsonNode n = d.get("containerYard");
            yard = new AssetDetails.ContainerYard(
                    n.path("maxTiers").asInt(),
                    dec(n, "designFill"),
                    n.path("capacityTeu").asInt());
        }
        AssetDetails.Warehouse warehouse = null;
        if (d.has("warehouse")) {
            JsonNode n = d.get("warehouse");
            warehouse = new AssetDetails.Warehouse(
                    n.hasNonNull("roofRiseM") ? dec(n, "roofRiseM") : BigDecimal.ZERO,
                    n.path("dockDoors").asInt(),
                    n.hasNonNull("doorSide") ? n.get("doorSide").asText() : "NORTH");
        }
        AssetDetails.Flow flow = null;
        if (d.has("flow")) {
            JsonNode n = d.get("flow");
            flow = new AssetDetails.Flow(dec(n, "rateValue"), n.path("rateUnit").asText());
        }
        AssetDetails.Surface surface = d.has("surface")
                ? new AssetDetails.Surface(d.get("surface").path("surfaceCode").asText())
                : null;
        AssetDetails.Zone zone =
                d.has("zone") ? new AssetDetails.Zone(strings(d.get("zone").path("allowedTypes"))) : null;
        AssetDetails.Mobile mobile = null;
        if (d.has("mobile")) {
            JsonNode n = d.get("mobile");
            mobile = new AssetDetails.Mobile(
                    n.path("positionMode").asText("PARKED"),
                    n.hasNonNull("uncertaintyM") ? dec(n, "uncertaintyM") : BigDecimal.valueOf(5));
        }
        AssetDetails.Equipment equipment = null;
        if (d.has("equipment")) {
            JsonNode n = d.get("equipment");
            equipment = new AssetDetails.Equipment(
                    n.path("model").asText(null),
                    n.path("operator").asText(null),
                    dec(n, "ratedThroughput"),
                    n.path("throughputUnit").asText(null),
                    n.path("designStatus").asText("ACTIVE"));
        }
        return new AssetDetails(berth, crane, storage, pad, yard, warehouse, flow, surface, zone, mobile, equipment);
    }

    private static BigDecimal dec(JsonNode parent, String field) {
        JsonNode n = parent.path(field);
        return n.isMissingNode() || n.isNull() ? null : n.decimalValue();
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static RuleViolationException bad(String pointer, String message) {
        return new RuleViolationException(
                "Invalid layout file", List.of(new RuleViolation("LAYOUT_FILE", null, pointer, message)));
    }
}

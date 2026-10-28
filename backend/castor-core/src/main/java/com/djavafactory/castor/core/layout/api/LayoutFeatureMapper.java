package com.djavafactory.castor.core.layout.api;

import java.math.BigDecimal;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.djavafactory.castor.core.geometry.application.GeoJsonMapper;
import com.djavafactory.castor.core.layout.domain.AssetDetails;
import com.djavafactory.castor.core.layout.domain.AssetMeasures;
import com.djavafactory.castor.core.layout.domain.LayoutAsset;
import com.djavafactory.castor.core.layout.domain.LayoutVersion;
import com.djavafactory.castor.core.layout.domain.LayoutView;

/** Builds the layout answers of CORE-04 part 5. Geometry always comes from the shared {@link GeoJsonMapper}. */
@ApplicationScoped
public class LayoutFeatureMapper {

    private final GeoJsonMapper geo;
    private final ObjectMapper json;

    public LayoutFeatureMapper(GeoJsonMapper geo, ObjectMapper json) {
        this.geo = geo;
        this.json = json;
    }

    /** {layout: {...}, features: FeatureCollection}. */
    public ObjectNode layout(LayoutView view) {
        ObjectNode out = json.createObjectNode();
        out.set("layout", header(view.version()));
        out.set(
                "features",
                geo.featureCollection(view.assets().stream()
                        .map(a -> feature(a, view.measures().get(a.id())))
                        .toList()));
        return out;
    }

    public ObjectNode header(LayoutVersion v) {
        ObjectNode h = json.createObjectNode();
        h.put("id", v.id().toString());
        h.put("versionNo", v.versionNo());
        h.put("status", v.status().name());
        h.put("publishedAt", v.publishedAt() == null ? null : v.publishedAt().toString());
        h.put("publishedBy", v.publishedBy());
        h.put("note", v.note());
        h.put("basedOnVersionNo", v.basedOnVersionNo());
        h.put("createdBy", v.createdBy());
        h.put("createdAt", v.createdAt().toString());
        h.put("updatedBy", v.updatedBy());
        h.put("updatedAt", v.updatedAt().toString());
        return h;
    }

    public ObjectNode feature(LayoutAsset a, AssetMeasures m) {
        ObjectNode p = json.createObjectNode();
        p.put("code", a.code());
        p.put("type", a.typeCode());
        p.put("name", a.name());
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
        if (a.modelRev() == null) {
            p.putNull("modelRev");
        } else {
            p.put("modelRev", a.modelRev());
        }
        p.put("storageZoneCode", a.storageZoneCode());
        p.put("operatorName", a.operatorName());
        p.put("description", a.description());
        p.set("details", details(a.details()));
        ArrayNode links = json.createArrayNode();
        a.links()
                .forEach(l -> links.addObject()
                        .put("kind", l.kind())
                        .put("toAssetId", l.toAssetId().toString()));
        p.set("links", links);
        ObjectNode prov = p.putObject("provenance");
        prov.put("source", a.provenance().source());
        prov.put("accuracy", a.provenance().accuracy());
        prov.put(
                "capturedOn",
                a.provenance().capturedOn() == null
                        ? null
                        : a.provenance().capturedOn().toString());
        prov.put("note", a.provenance().note());
        if (a.override() == null) {
            p.putNull("override");
        } else {
            p.putObject("override")
                    .put("reason", a.override().reason())
                    .put("by", a.override().by())
                    .put("at", a.override().at().toString());
        }
        p.set("computed", computed(m));
        p.put("rowVersion", a.rowVersion());
        return geo.feature(a.id().toString(), a.geom(), p);
    }

    private ObjectNode computed(AssetMeasures m) {
        ObjectNode c = json.createObjectNode();
        if (m == null) {
            return c;
        }
        put(c, "areaM2", m.areaM2());
        put(c, "perimeterM", m.perimeterM());
        put(c, "lengthM", m.lengthM());
        c.putObject("local")
                .put("x", rounded(m.local().x()))
                .put("y", rounded(m.local().y()));
        if (m.chainage() != null) {
            c.putObject("chainage")
                    .put("startM", rounded(m.chainage().startM()))
                    .put("endM", rounded(m.chainage().endM()));
        }
        return c;
    }

    private ObjectNode details(AssetDetails d) {
        ObjectNode out = json.createObjectNode();
        if (d.berth() != null) {
            ObjectNode n = out.putObject("berth");
            number(n, "depthM", d.berth().depthM());
            number(n, "maxLoaM", d.berth().maxLoaM());
            strings(n.putArray("cargoFamilies"), d.berth().cargoFamilies());
        }
        if (d.crane() != null) {
            ObjectNode n = out.putObject("crane");
            number(n, "gaugeM", d.crane().gaugeM());
            number(n, "outreachM", d.crane().outreachM());
            number(n, "backreachM", d.crane().backreachM());
            number(n, "reachM", d.crane().reachM());
            number(n, "safeWorkingLoadT", d.crane().safeWorkingLoadT());
        }
        if (d.storage() != null) {
            var s = d.storage();
            ObjectNode n = out.putObject("storage");
            number(n, "capacityValue", s.capacityValue());
            n.put("capacityUnit", s.capacityUnit());
            number(n, "usableRatio", s.usableRatio());
            number(n, "usableCapacity", s.usableCapacity());
            number(n, "designFill", s.designFill());
            n.put("cargoTypeCode", s.cargoTypeCode());
            strings(n.putArray("allowedCargoTypes"), s.allowedCargoTypes());
            number(n, "maxFloorLoadTM2", s.maxFloorLoadTM2());
            number(n, "maxStackM", s.maxStackM());
            number(n, "bearingCapacityKpa", s.bearingCapacityKpa());
            n.put("weatherExposed", s.weatherExposed());
        }
        if (d.storagePad() != null) {
            var s = d.storagePad();
            ObjectNode n = out.putObject("storagePad");
            n.put("bays", s.bays());
            n.put("rows", s.rows());
            number(n, "bayPitchM", s.bayPitchM());
            number(n, "rowPitchM", s.rowPitchM());
            number(n, "maxHeightM", s.maxHeightM());
            put(n, "capacityT", s.capacityT());
        }
        if (d.containerYard() != null) {
            ObjectNode n = out.putObject("containerYard");
            n.put("maxTiers", d.containerYard().maxTiers());
            number(n, "designFill", d.containerYard().designFill());
            n.put("capacityTeu", d.containerYard().capacityTeu());
        }
        if (d.warehouse() != null) {
            ObjectNode n = out.putObject("warehouse");
            number(n, "roofRiseM", d.warehouse().roofRiseM());
            n.put("dockDoors", d.warehouse().dockDoors());
            n.put("doorSide", d.warehouse().doorSide());
        }
        if (d.flow() != null) {
            ObjectNode n = out.putObject("flow");
            number(n, "rateValue", d.flow().rateValue());
            n.put("rateUnit", d.flow().rateUnit());
        }
        if (d.surface() != null) {
            out.putObject("surface").put("surfaceCode", d.surface().surfaceCode());
        }
        if (d.zone() != null) {
            strings(out.putObject("zone").putArray("allowedTypes"), d.zone().allowedTypes());
        }
        if (d.mobile() != null) {
            ObjectNode n = out.putObject("mobile");
            n.put("positionMode", d.mobile().positionMode());
            number(n, "uncertaintyM", d.mobile().uncertaintyM());
        }
        if (d.equipment() != null) {
            var e = d.equipment();
            ObjectNode n = out.putObject("equipment");
            n.put("model", e.model());
            n.put("operator", e.operator());
            number(n, "ratedThroughput", e.ratedThroughput());
            n.put("throughputUnit", e.throughputUnit());
            n.put("designStatus", e.designStatus());
        }
        return out;
    }

    /** Decimal columns are written as JSON numbers without trailing zeros; null stays null. */
    private static void number(ObjectNode node, String field, BigDecimal value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value.stripTrailingZeros().doubleValue());
        }
    }

    private static void put(ObjectNode node, String field, Double value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, rounded(value));
        }
    }

    private static void strings(ArrayNode array, List<String> values) {
        values.forEach(array::add);
    }

    private static double rounded(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}

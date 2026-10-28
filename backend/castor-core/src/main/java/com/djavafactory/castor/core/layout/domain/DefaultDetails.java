package com.djavafactory.castor.core.layout.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.djavafactory.castor.core.layout.domain.AssetDetails.Berth;
import com.djavafactory.castor.core.layout.domain.AssetDetails.ContainerYard;
import com.djavafactory.castor.core.layout.domain.AssetDetails.Crane;
import com.djavafactory.castor.core.layout.domain.AssetDetails.Flow;
import com.djavafactory.castor.core.layout.domain.AssetDetails.Mobile;
import com.djavafactory.castor.core.layout.domain.AssetDetails.StoragePad;
import com.djavafactory.castor.core.layout.domain.AssetDetails.Surface;
import com.djavafactory.castor.core.layout.domain.AssetDetails.Warehouse;
import com.djavafactory.castor.core.layout.domain.AssetDetails.Zone;

/**
 * The details a newly placed asset gets from its type (asset_type.params and defaults), so a palette placement with only
 * anchor, heading and size is complete. Details the caller sends win group by group.
 */
public final class DefaultDetails {

    private DefaultDetails() {}

    public static AssetDetails of(AssetType type) {
        Map<String, Object> p = type.params();
        Berth berth = null;
        Crane crane = null;
        StoragePad pad = null;
        ContainerYard yard = null;
        Warehouse warehouse = null;
        Flow flow = null;
        Surface surface = null;
        Zone zone = null;
        Mobile mobile = null;
        switch (type.code()) {
            case "BERTH" -> berth = new Berth(num(p, "depthM", 12), null, List.of());
            case "QUAY_CRANE_RAIL" ->
                crane = new Crane(num(p, "gaugeM", 16), num(p, "outreachM", 65), num(p, "backreachM", 32), null, null);
            case "WAREHOUSE" ->
                warehouse = new Warehouse(
                        num(p, "roofRiseM", 0), (int) num(p, "dockDoors", 0).longValue());
            case "STORAGE_PAD" -> {
                int bays = (int) num(p, "bays", 1).longValue();
                int rows = (int) num(p, "rows", 1).longValue();
                BigDecimal bay = num(
                        p,
                        "pitchLengthM",
                        type.defaults().lengthM() == null
                                ? 10
                                : type.defaults().lengthM().doubleValue() / bays);
                BigDecimal row = num(
                        p,
                        "pitchWidthM",
                        type.defaults().widthM() == null
                                ? 10
                                : type.defaults().widthM().doubleValue() / rows);
                pad = new StoragePad(bays, rows, bay, row, num(p, "maxHeightM", 5));
            }
            case "CONTAINER_YARD" ->
                yard = new ContainerYard(
                        (int) num(p, "tiers", 4).longValue(),
                        new BigDecimal("0.8"),
                        type.defaultCapacityValue() == null
                                ? 0
                                : type.defaultCapacityValue().intValue());
            case "CONVEYOR", "PIPELINE" -> {
                String unit = "PIPELINE".equals(type.code()) ? "m3/h" : "t/h";
                flow = new Flow(num(p, p.containsKey("rateTph") ? "rateTph" : "rateM3h", 100), unit);
            }
            case "SURFACE_AREA" ->
                surface = new Surface(
                        p.get("surface") == null ? "CONCRETE" : p.get("surface").toString());
            case "EXCLUSION_ZONE" -> zone = new Zone(List.of());
            default -> {}
        }
        if (type.flags().mobile()) {
            mobile = new Mobile("PARKED", new BigDecimal("5"));
        }
        return new AssetDetails(berth, crane, null, pad, yard, warehouse, flow, surface, zone, mobile, null);
    }

    /** The given details with every group the caller left out taken from the defaults. */
    public static AssetDetails withDefaults(AssetDetails given, AssetDetails defaults) {
        return new AssetDetails(
                pick(given.berth(), defaults.berth()),
                pick(given.crane(), defaults.crane()),
                pick(given.storage(), defaults.storage()),
                pick(given.storagePad(), defaults.storagePad()),
                pick(given.containerYard(), defaults.containerYard()),
                pick(given.warehouse(), defaults.warehouse()),
                pick(given.flow(), defaults.flow()),
                pick(given.surface(), defaults.surface()),
                pick(given.zone(), defaults.zone()),
                pick(given.mobile(), defaults.mobile()),
                pick(given.equipment(), defaults.equipment()));
    }

    private static <T> T pick(T given, T fallback) {
        return given != null ? given : fallback;
    }

    private static BigDecimal num(Map<String, Object> params, String key, double fallback) {
        Object v = params == null ? null : params.get(key);
        return v instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : BigDecimal.valueOf(fallback);
    }
}

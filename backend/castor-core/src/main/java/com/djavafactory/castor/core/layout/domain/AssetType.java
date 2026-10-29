package com.djavafactory.castor.core.layout.domain;

import java.math.BigDecimal;
import java.util.Map;

/**
 * One placeable asset type: its palette group, geometry, default size, limits and 3D model. The frontend palette and
 * the backend rules read the same row, so a new type is a migration plus a 3D model.
 *
 * @param params type specific defaults without a column of their own (reach, outreach, tiers, rate ...)
 * @param purpose what the asset is for, in one sentence (the palette shows it with the thumbnail)
 */
public record AssetType(
        String code,
        String label,
        String paletteGroup,
        String category,
        String geometryKind,
        String placementMode,
        Flags flags,
        Sizes defaults,
        Sizes min,
        Sizes max,
        BigDecimal defaultCapacityValue,
        String defaultCapacityUnit,
        Map<String, Object> params,
        String modelKey,
        int modelRev,
        String colourHex,
        String hint,
        String purpose,
        int sortOrder) {

    /** S solid, F flat, Z zone, M mobile, W may extend over water, B must be on berth. */
    public record Flags(
            boolean mobile,
            boolean solid,
            boolean flat,
            boolean zone,
            boolean mayExtendOverWater,
            boolean mustBeOnBerth) {}

    /** Any of the sizes may be absent; line width is only used by defaults. */
    public record Sizes(
            BigDecimal lengthM, BigDecimal widthM, BigDecimal heightM, BigDecimal radiusM, BigDecimal lineWidthM) {}
}

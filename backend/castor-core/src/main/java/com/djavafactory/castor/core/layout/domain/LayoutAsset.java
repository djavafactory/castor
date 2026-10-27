package com.djavafactory.castor.core.layout.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.locationtech.jts.geom.Geometry;

import com.djavafactory.castor.core.geometry.domain.LonLat;

/**
 * One asset as it is in one layout version: its permanent identity, the stored geometry and the typed details.
 * Measurements are not part of it; they are computed when a version is read ({@link AssetMeasures}).
 */
public record LayoutAsset(
        UUID id,
        String code,
        String typeCode,
        String name,
        Geometry geom,
        LonLat anchor,
        BigDecimal headingDeg,
        BigDecimal lengthM,
        BigDecimal widthM,
        BigDecimal heightM,
        BigDecimal radiusM,
        BigDecimal lineWidthM,
        BigDecimal elevationM,
        BigDecimal clearanceM,
        Integer modelRev,
        String storageZoneCode,
        String operatorName,
        String description,
        Provenance provenance,
        Override override,
        AssetDetails details,
        List<Link> links,
        long rowVersion) {

    public record Provenance(String source, String accuracy, LocalDate capturedOn, String note) {}

    /** Recorded only when an exclusion-zone override was approved. */
    public record Override(String reason, String by, Instant at) {}

    public record Link(String kind, UUID toAssetId) {}
}

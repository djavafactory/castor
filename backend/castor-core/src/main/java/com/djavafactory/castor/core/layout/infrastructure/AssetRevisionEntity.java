package com.djavafactory.castor.core.layout.infrastructure;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;

/**
 * A row of layout.asset_revision: one asset in one layout version. Geometry is JTS with SRID 4326; the mapping never
 * rounds a coordinate (rounding is CORE-01's job before the value gets here).
 */
@Entity
@Table(schema = "layout", name = "asset_revision")
public class AssetRevisionEntity {

    @EmbeddedId
    public AssetRevisionId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id", insertable = false, updatable = false)
    public AssetEntity asset;

    @Column(nullable = false)
    public String name;

    @Column(nullable = false, columnDefinition = "geometry(Geometry,4326)")
    public Geometry geom;

    @Column(nullable = false, columnDefinition = "geometry(Point,4326)")
    public Point anchor;

    @Column(name = "heading_deg", precision = 7, scale = 3)
    public BigDecimal headingDeg;

    @Column(name = "length_m", precision = 10, scale = 3)
    public BigDecimal lengthM;

    @Column(name = "width_m", precision = 10, scale = 3)
    public BigDecimal widthM;

    @Column(name = "height_m", precision = 10, scale = 3)
    public BigDecimal heightM;

    @Column(name = "radius_m", precision = 10, scale = 3)
    public BigDecimal radiusM;

    @Column(name = "line_width_m", precision = 10, scale = 3)
    public BigDecimal lineWidthM;

    @Column(name = "elevation_m", nullable = false, precision = 8, scale = 3)
    public BigDecimal elevationM = BigDecimal.ZERO;

    @Column(name = "clearance_m", nullable = false, precision = 8, scale = 3)
    public BigDecimal clearanceM = BigDecimal.ZERO;

    @Column(name = "model_rev")
    public Integer modelRev;

    @Column(name = "storage_zone_code")
    public String storageZoneCode;

    @Column(name = "operator_name")
    public String operatorName;

    public String description;

    @Column(name = "accuracy_code", nullable = false, length = 16)
    public String accuracyCode;

    @Column(name = "source_code", nullable = false, length = 32)
    public String sourceCode;

    @Column(name = "captured_on")
    public LocalDate capturedOn;

    @Column(name = "provenance_note")
    public String provenanceNote;

    @Column(name = "override_reason")
    public String overrideReason;

    @Column(name = "override_by")
    public String overrideBy;

    @Column(name = "override_at")
    public Instant overrideAt;

    @Version
    @Column(name = "row_version", nullable = false)
    public long rowVersion;

    @Column(name = "created_by", nullable = false, length = 64)
    public String createdBy;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_by", nullable = false, length = 64)
    public String updatedBy;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();
}

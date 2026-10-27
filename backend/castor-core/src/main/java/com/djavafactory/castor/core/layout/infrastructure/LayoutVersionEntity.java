package com.djavafactory.castor.core.layout.infrastructure;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.djavafactory.castor.core.layout.domain.LayoutStatus;

/** A row of layout.layout_version. */
@Entity
@Table(schema = "layout", name = "layout_version")
public class LayoutVersionEntity {

    @Id
    public UUID id;

    @Column(name = "port_id", nullable = false)
    public Long portId;

    @Column(name = "version_no", nullable = false)
    public int versionNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    public LayoutStatus status;

    @Column(nullable = false, length = 16)
    public String crs = "EPSG:4326";

    @Column(name = "based_on_version_id")
    public UUID basedOnVersionId;

    @Column(name = "restored_from_version_id")
    public UUID restoredFromVersionId;

    public String note;

    @Column(name = "created_by", nullable = false, length = 64)
    public String createdBy;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "reviewed_by", length = 64)
    public String reviewedBy;

    @Column(name = "published_by", length = 64)
    public String publishedBy;

    @Column(name = "published_at")
    public Instant publishedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    public long rowVersion;
}

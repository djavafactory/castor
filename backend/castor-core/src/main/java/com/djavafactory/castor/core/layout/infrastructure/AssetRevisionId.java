package com.djavafactory.castor.core.layout.infrastructure;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** Primary key of layout.asset_revision. */
@Embeddable
public class AssetRevisionId implements Serializable {

    @Column(name = "asset_id", nullable = false)
    public UUID assetId;

    @Column(name = "layout_version_id", nullable = false)
    public UUID layoutVersionId;

    public AssetRevisionId() {}

    public AssetRevisionId(UUID assetId, UUID layoutVersionId) {
        this.assetId = assetId;
        this.layoutVersionId = layoutVersionId;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AssetRevisionId other
                && Objects.equals(assetId, other.assetId)
                && Objects.equals(layoutVersionId, other.layoutVersionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(assetId, layoutVersionId);
    }
}

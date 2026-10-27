package com.djavafactory.castor.core.layout.domain;

import java.util.List;

/**
 * A feature about to be stored: the asset (id not yet known, no links) and its links by asset code. Seed files and
 * imports produce drafts; the same {@link LayoutFeatureValidator} checks them before anything is written.
 */
public record FeatureDraft(LayoutAsset asset, List<DraftLink> links) {

    public record DraftLink(String kind, String toCode) {}

    public String code() {
        return asset.code();
    }
}

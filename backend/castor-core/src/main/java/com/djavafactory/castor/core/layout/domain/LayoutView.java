package com.djavafactory.castor.core.layout.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A layout version with all its assets, ready to be mapped. {@code etagSource} changes whenever the version or any
 * of its revisions changes.
 */
public record LayoutView(
        LayoutVersion version, List<LayoutAsset> assets, Map<UUID, AssetMeasures> measures, String etagSource) {}

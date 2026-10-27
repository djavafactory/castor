package com.djavafactory.castor.core.layout.domain;

import java.util.List;

/** Reads layout.asset_type. Implemented in infrastructure. */
public interface AssetTypeRepository {

    /** Every type, ordered by palette group then sort order. */
    List<AssetType> findAll();
}

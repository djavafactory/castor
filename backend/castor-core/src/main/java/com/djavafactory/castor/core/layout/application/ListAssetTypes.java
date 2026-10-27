package com.djavafactory.castor.core.layout.application;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.core.layout.domain.AssetType;
import com.djavafactory.castor.core.layout.domain.AssetTypeRepository;

/** Use case: the asset type registry that drives the palette and the placement rules. */
@ApplicationScoped
public class ListAssetTypes {

    private final AssetTypeRepository repository;

    public ListAssetTypes(AssetTypeRepository repository) {
        this.repository = repository;
    }

    public List<AssetType> execute() {
        return repository.findAll();
    }
}

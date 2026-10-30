package com.djavafactory.castor.core.operations.application;

import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import com.djavafactory.castor.core.operations.api.AssetStateDto;
import com.djavafactory.castor.core.operations.api.EquipmentStateDto;
import com.djavafactory.castor.core.operations.api.InventoryDto;
import com.djavafactory.castor.core.operations.domain.EquipmentRepository;
import com.djavafactory.castor.core.operations.domain.InventoryRepository;
import com.djavafactory.castor.core.operations.domain.StateReadRepository;
import com.djavafactory.castor.core.shared.domain.ResourceNotFoundException;

/** The state of one asset (a machine or a storage area) with the canonical events it came from. */
@ApplicationScoped
public class ReadAssetState {

    static final int LINEAGE_LIMIT = 20;

    private final EquipmentRepository equipment;
    private final InventoryRepository inventories;
    private final StateReadRepository reads;
    private final StateViews views;

    ReadAssetState(
            EquipmentRepository equipment,
            InventoryRepository inventories,
            StateReadRepository reads,
            StateViews views) {
        this.equipment = equipment;
        this.inventories = inventories;
        this.reads = reads;
        this.views = views;
    }

    @Transactional
    public AssetStateDto execute(long portId, UUID assetId) {
        EquipmentStateDto machine =
                equipment.find(portId, assetId).map(views::equipment).orElse(null);
        List<InventoryDto> stock = inventories.findByStorage(portId, assetId).stream()
                .map(views::inventory)
                .toList();
        if (machine == null && stock.isEmpty() && !reads.assetExists(portId, assetId)) {
            throw new ResourceNotFoundException("No asset " + assetId + " in this port");
        }
        String kind = machine != null ? "EQUIPMENT" : stock.isEmpty() ? "NONE" : "STORAGE";
        var lineage = reads.lineage(portId, List.of(assetId.toString()), LINEAGE_LIMIT).stream()
                .map(views::lineage)
                .toList();
        return new AssetStateDto(assetId, kind, machine, stock, lineage);
    }
}

package com.djavafactory.castor.core.operations.api;

import java.util.List;
import java.util.UUID;

/**
 * The state of one asset and where it came from. {@code kind} is EQUIPMENT, STORAGE or NONE (the asset exists but no
 * state was reported yet); lineage is the last 20 canonical events, newest first.
 */
public record AssetStateDto(
        UUID assetId,
        String kind,
        EquipmentStateDto equipment,
        List<InventoryDto> inventories,
        List<LineageEntryDto> lineage) {}

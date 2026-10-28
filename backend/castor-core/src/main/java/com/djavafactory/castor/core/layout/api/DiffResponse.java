package com.djavafactory.castor.core.layout.api;

import java.util.List;
import java.util.UUID;

import com.djavafactory.castor.core.layout.domain.LayoutDiff;

/** {added, removed, changed} of GET /layouts/{id}/diff. */
public record DiffResponse(List<Item> added, List<Item> removed, List<Changed> changed) {

    public record Item(UUID assetId, String code, String name, String type) {}

    /** @param fields names of the properties that differ, for example "geometry" or "heightM" */
    public record Changed(UUID assetId, String code, String name, List<String> fields) {}

    static DiffResponse of(LayoutDiff diff) {
        return new DiffResponse(
                diff.added().stream().map(DiffResponse::item).toList(),
                diff.removed().stream().map(DiffResponse::item).toList(),
                diff.changed().stream()
                        .map(c -> new Changed(c.assetId(), c.code(), c.name(), c.fields()))
                        .toList());
    }

    private static Item item(LayoutDiff.Entry e) {
        return new Item(e.assetId(), e.code(), e.name(), e.typeCode());
    }
}

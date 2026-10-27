package com.djavafactory.castor.core.port.domain;

import java.math.BigDecimal;
import java.util.List;

/** Cargo and ship catalogue plus the small lookups the palette and inspector show. */
public record Catalogue(
        List<CargoType> cargoTypes,
        List<ShipClass> shipClasses,
        List<CargoFamilyInfo> cargoFamilies,
        List<SurfaceInfo> surfaces,
        List<AccuracyClassInfo> accuracyClasses,
        List<AssetCategoryInfo> assetCategories,
        List<PaletteGroupInfo> paletteGroups,
        String densityNote) {

    public static final String DENSITY_NOTE = "Densities are typical values, not a standard.";

    public record CargoType(
            String code,
            String name,
            String familyCode,
            String baseUnit,
            BigDecimal densityTM3,
            String storageForm,
            BigDecimal maxStackM,
            BigDecimal reposeDeg,
            String colourHex) {}

    public record ShipClass(
            String code, String name, String kind, BigDecimal loaM, BigDecimal beamM, BigDecimal draftM, int dwtT) {}

    public record CargoFamilyInfo(String code, String label) {}

    public record SurfaceInfo(String code, String label, String colourHex) {}

    public record AccuracyClassInfo(String code, String label, String colourHex, int sortOrder) {}

    public record AssetCategoryInfo(String code, String label, int sortOrder) {}

    public record PaletteGroupInfo(String code, String label, int sortOrder) {}
}

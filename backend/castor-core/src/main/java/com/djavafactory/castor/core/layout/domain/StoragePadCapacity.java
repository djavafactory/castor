package com.djavafactory.castor.core.layout.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Capacity of a storage pad, computed in the domain and shown read-only (CORE-04 part 2, rule 12). A pad is a grid of
 * slots; each slot is the pitch reduced by 6 % for the aisle.
 */
public final class StoragePadCapacity {

    private static final double SLOT_FACTOR = 0.94;
    private static final double PILE_SIDE_RATIO = 0.15;

    /** The cargo facts the rules need (ref.cargo_type). maxStackM and reposeDeg are null when not applicable. */
    public record Cargo(String storageForm, double densityTPerM3, Double maxStackM, Double reposeDeg) {}

    private StoragePadCapacity() {}

    /** Shape factor of a pile or stack with the given relative top lengths. */
    static double k(double rx, double rz) {
        return (1 + 4 * ((1 + rx) / 2) * ((1 + rz) / 2) + rx * rz) / 6;
    }

    /** Tonnes in one slot, or 0 when the cargo is not stored on a pad (tank, unit, parking). */
    public static double slotTonnes(double bayPitchM, double rowPitchM, double maxHeightM, Cargo cargo) {
        double l = bayPitchM * SLOT_FACTOR;
        double w = rowPitchM * SLOT_FACTOR;
        double volume;
        switch (cargo.storageForm()) {
            case "PILE" -> {
                double h = Math.min(
                        maxHeightM, Math.tan(Math.toRadians(cargo.reposeDeg())) * ((1 - PILE_SIDE_RATIO) / 2) * w);
                volume = k(0.55, 0.15) * l * w * h;
            }
            case "STACK" -> {
                double h = Math.min(maxHeightM, cargo.maxStackM());
                volume = k(0.92, 0.92) * l * w * h;
            }
            case "SILO" -> {
                double d = Math.min(l, w);
                volume = Math.PI / 4 * d * d * (2 * maxHeightM);
            }
            default -> {
                return 0;
            }
        }
        return volume * cargo.densityTPerM3();
    }

    /** Tonnes on the whole pad. */
    public static double padTonnes(AssetDetails.StoragePad pad, Cargo cargo) {
        return pad.bays()
                * pad.rows()
                * slotTonnes(
                        pad.bayPitchM().doubleValue(),
                        pad.rowPitchM().doubleValue(),
                        pad.maxHeightM().doubleValue(),
                        cargo);
    }

    /** Footprint of the pad: (bays x bay pitch) by (rows x row pitch), as {length, width}. */
    public static double[] footprint(AssetDetails.StoragePad pad) {
        return new double[] {
            pad.bays() * pad.bayPitchM().doubleValue(),
            pad.rows() * pad.rowPitchM().doubleValue()
        };
    }

    /** Slot codes PAD-A1-01-1: pad code, bay (2 digits), row. */
    public static List<String> zoneCodes(String padCode, int bays, int rows) {
        var codes = new ArrayList<String>(bays * rows);
        for (int row = 1; row <= rows; row++) {
            for (int bay = 1; bay <= bays; bay++) {
                codes.add(String.format(Locale.ROOT, "%s-%02d-%d", padCode, bay, row));
            }
        }
        return codes;
    }
}

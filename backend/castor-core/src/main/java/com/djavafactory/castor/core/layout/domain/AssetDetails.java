package com.djavafactory.castor.core.layout.domain;

import java.math.BigDecimal;
import java.util.List;

/** The typed details of one asset revision. Every group is null unless the asset type has that kind of detail. */
public record AssetDetails(
        Berth berth,
        Crane crane,
        Storage storage,
        StoragePad storagePad,
        ContainerYard containerYard,
        Warehouse warehouse,
        Flow flow,
        Surface surface,
        Zone zone,
        Mobile mobile,
        Equipment equipment) {

    public static final AssetDetails NONE =
            new AssetDetails(null, null, null, null, null, null, null, null, null, null, null);

    /** Depth is the maximum draft; maxLoaM defaults to the berth length when absent. */
    public record Berth(BigDecimal depthM, BigDecimal maxLoaM, List<String> cargoFamilies) {}

    public record Crane(
            BigDecimal gaugeM,
            BigDecimal outreachM,
            BigDecimal backreachM,
            BigDecimal reachM,
            BigDecimal safeWorkingLoadT) {}

    /**
     * Static design values only: usable capacity is capacity x usableRatio. Stock is never stored in Phase 1.
     *
     * @param weatherExposed true for open storage, false when covered
     */
    public record Storage(
            BigDecimal capacityValue,
            String capacityUnit,
            BigDecimal usableRatio,
            BigDecimal designFill,
            String cargoTypeCode,
            List<String> allowedCargoTypes,
            BigDecimal maxFloorLoadTM2,
            BigDecimal maxStackM,
            BigDecimal bearingCapacityKpa,
            boolean weatherExposed) {

        public BigDecimal usableCapacity() {
            return capacityValue.multiply(usableRatio);
        }
    }

    /** capacityT is computed from the cargo of the pad (StoragePadCapacity), read-only, null when no cargo is set. */
    public record StoragePad(
            int bays, int rows, BigDecimal bayPitchM, BigDecimal rowPitchM, BigDecimal maxHeightM, Double capacityT) {

        public StoragePad(int bays, int rows, BigDecimal bayPitchM, BigDecimal rowPitchM, BigDecimal maxHeightM) {
            this(bays, rows, bayPitchM, rowPitchM, maxHeightM, null);
        }
    }

    public record ContainerYard(int maxTiers, BigDecimal designFill, int capacityTeu) {}

    /**
     * @param doorSide the compass side the dock doors face: NORTH, EAST, SOUTH or WEST, unrotated with the asset
     */
    public record Warehouse(BigDecimal roofRiseM, int dockDoors, String doorSide) {}

    public record Flow(BigDecimal rateValue, String rateUnit) {}

    public record Surface(String surfaceCode) {}

    public record Zone(List<String> allowedTypes) {}

    /** LIVE positions come in Phase 2; Phase 1 knows FIXED and PARKED. */
    public record Mobile(String positionMode, BigDecimal uncertaintyM) {}

    /** designStatus is a static design value, never live status. */
    public record Equipment(
            String model, String operator, BigDecimal ratedThroughput, String throughputUnit, String designStatus) {}
}

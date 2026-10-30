package com.djavafactory.castor.core.operations.application;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.djavafactory.castor.contracts.Quantity;
import com.djavafactory.castor.core.operations.api.CargoLotDto;
import com.djavafactory.castor.core.operations.api.EquipmentStateDto;
import com.djavafactory.castor.core.operations.api.GateVisitDto;
import com.djavafactory.castor.core.operations.api.InventoryDto;
import com.djavafactory.castor.core.operations.api.LineageEntryDto;
import com.djavafactory.castor.core.operations.api.VesselCallDto;
import com.djavafactory.castor.core.operations.domain.CargoLot;
import com.djavafactory.castor.core.operations.domain.EquipmentState;
import com.djavafactory.castor.core.operations.domain.GateVisit;
import com.djavafactory.castor.core.operations.domain.LineageEntry;
import com.djavafactory.castor.core.operations.domain.MassConversion;
import com.djavafactory.castor.core.operations.domain.ObservedLevel;
import com.djavafactory.castor.core.operations.domain.PublishedAssets;
import com.djavafactory.castor.core.operations.domain.StorageInventory;
import com.djavafactory.castor.core.operations.domain.VesselCall;
import com.djavafactory.castor.core.shared.infrastructure.CastorConfig;

/**
 * The one place that turns state into the public DTOs (R2, R3): the snapshot, the state endpoints and the projections
 * that record state changes all go through it, so a delta body is exactly one snapshot item.
 */
@ApplicationScoped
public class StateViews {

    private final CastorConfig.Sla sla;
    private final PublishedAssets assets;

    @Inject
    StateViews(CastorConfig castor, PublishedAssets assets) {
        this(castor.state().sla(), assets);
    }

    StateViews(CastorConfig.Sla sla, PublishedAssets assets) {
        this.sla = sla;
        this.assets = assets;
    }

    public EquipmentStateDto equipment(EquipmentState e) {
        var p = e.position();
        var position = p == null
                ? null
                : new EquipmentStateDto.Position(p.lon(), p.lat(), p.headingDeg(), p.accuracyM(), p.observedAt());
        return new EquipmentStateDto(
                e.assetId().toString(),
                e.assetId(),
                e.status(),
                e.statusReason(),
                position,
                e.assignment(),
                e.provenance().quality(),
                e.provenance().source(),
                e.provenance().observedAt(),
                sla.equipment().toSeconds(),
                e.lastOccurredAt(),
                e.version());
    }

    public VesselCallDto vesselCall(VesselCall c) {
        return new VesselCallDto(
                c.id().toString(),
                c.callCode(),
                c.vesselName(),
                c.imo(),
                c.loaM(),
                c.beamM(),
                c.status(),
                c.eta(),
                c.ata(),
                c.etd(),
                c.atd(),
                c.berthAssetId(),
                c.provenance().quality(),
                c.provenance().source(),
                c.provenance().observedAt(),
                sla.vesselCall().toSeconds(),
                c.lastOccurredAt(),
                c.version());
    }

    public InventoryDto inventory(StorageInventory i) {
        Quantity book = i.quantity();
        ObservedLevel level = i.level();
        // The mass of a book balance counted in volume uses the density of the last observation of the same tank
        BigDecimal density = level == null ? null : level.densityKgM3();
        var capacity = assets.usableCapacity(i.portId(), i.storageAssetId())
                .map(c -> new InventoryDto.Capacity("KNOWN", c.value(), c.unit()))
                .orElseGet(InventoryDto.Capacity::unknown);
        return new InventoryDto(
                i.storageAssetId() + ":" + i.commodity(),
                i.storageAssetId(),
                i.commodity(),
                book == null ? null : book.value(),
                book == null ? null : book.unit(),
                i.quality(),
                i.source(),
                i.quantityObservedAt(),
                sla.inventory().toSeconds(),
                i.version(),
                i.massBasis(),
                i.needsReconciliation(),
                capacity,
                level == null
                        ? null
                        : new InventoryDto.Observed(
                                level.quantity().value(),
                                level.quantity().unit(),
                                level.observedAt(),
                                level.source(),
                                level.quality(),
                                level.densityKgM3(),
                                level.temperatureC()),
                InventoryDto.Mass.of(MassConversion.tonnes(book, density).orElse(null)),
                InventoryDto.Mass.of(MassConversion.tonnes(level == null ? null : level.quantity(), density)
                        .orElse(null)));
    }

    public CargoLotDto cargoLot(CargoLot l) {
        return new CargoLotDto(
                l.id().toString(),
                l.lotCode(),
                l.consignmentCode(),
                l.cargoFamily(),
                l.commodity(),
                l.unit(),
                l.registeredQuantity(),
                l.massBasis(),
                l.currentLocationId(),
                l.quality(),
                l.source(),
                l.locationOccurredAt(),
                sla.inventory().toSeconds(),
                l.version());
    }

    public GateVisitDto gateVisit(GateVisit g) {
        Instant observedAt = g.outAt() != null ? g.outAt() : g.inAt();
        return new GateVisitDto(
                g.truckVisitId() + ":" + g.gateAssetId(),
                g.truckVisitId(),
                g.gateAssetId(),
                g.inAt(),
                g.outAt(),
                g.lane(),
                g.quality(),
                g.source(),
                observedAt,
                sla.gateVisit().toSeconds(),
                g.version());
    }

    public LineageEntryDto lineage(LineageEntry l) {
        return new LineageEntryDto(l.type(), l.occurredAt(), l.source(), l.quality());
    }
}

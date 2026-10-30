package com.djavafactory.castor.core.operations.application;

import java.time.Instant;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.djavafactory.castor.core.operations.api.EquipmentStateDto;
import com.djavafactory.castor.core.operations.api.GateVisitDto;
import com.djavafactory.castor.core.operations.api.InventoryDto;
import com.djavafactory.castor.core.operations.api.LineageEntryDto;
import com.djavafactory.castor.core.operations.api.VesselCallDto;
import com.djavafactory.castor.core.operations.domain.EquipmentState;
import com.djavafactory.castor.core.operations.domain.GateVisit;
import com.djavafactory.castor.core.operations.domain.LineageEntry;
import com.djavafactory.castor.core.operations.domain.VesselCall;
import com.djavafactory.castor.core.shared.infrastructure.CastorConfig;

/**
 * The one place that turns state into the public DTOs (R2, R3): the snapshot, the state endpoints and the projections
 * that record state changes all go through it, so a delta body is exactly one snapshot item.
 */
@ApplicationScoped
public class StateViews {

    private final CastorConfig.Sla sla;

    @Inject
    StateViews(CastorConfig castor) {
        this(castor.state().sla());
    }

    StateViews(CastorConfig.Sla sla) {
        this.sla = sla;
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

    public InventoryDto inventory(com.djavafactory.castor.core.operations.domain.StorageInventory i) {
        return new InventoryDto(
                i.storageAssetId() + ":" + i.commodity(),
                i.storageAssetId(),
                i.commodity(),
                i.quantity().value(),
                i.quantity().unit(),
                i.quality(),
                i.source(),
                i.quantityObservedAt(),
                sla.inventory().toSeconds(),
                i.version());
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
                sla.gateVisit().toSeconds());
    }

    public LineageEntryDto lineage(LineageEntry l) {
        return new LineageEntryDto(l.type(), l.occurredAt(), l.source(), l.quality());
    }
}

package com.djavafactory.castor.core.operations.application;

import java.time.InstantSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import com.djavafactory.castor.core.operations.api.SnapshotDto;
import com.djavafactory.castor.core.operations.domain.EquipmentRepository;
import com.djavafactory.castor.core.operations.domain.StateChangeRepository;
import com.djavafactory.castor.core.operations.domain.StateReadRepository;
import com.djavafactory.castor.core.shared.domain.ClockInfo;
import com.djavafactory.castor.core.shared.infrastructure.CastorConfig;

/** One consistent picture of the port for a browser that is about to follow the stream. */
@ApplicationScoped
public class ReadSnapshot {

    private final StateReadRepository reads;
    private final EquipmentRepository equipment;
    private final StateChangeRepository changes;
    private final StateViews views;
    private final InstantSource clock;
    private final ClockInfo clockInfo;
    private final CastorConfig castor;

    ReadSnapshot(
            StateReadRepository reads,
            EquipmentRepository equipment,
            StateChangeRepository changes,
            StateViews views,
            InstantSource clock,
            ClockInfo clockInfo,
            CastorConfig castor) {
        this.reads = reads;
        this.equipment = equipment;
        this.changes = changes;
        this.views = views;
        this.clock = clock;
        this.clockInfo = clockInfo;
        this.castor = castor;
    }

    /**
     * Everything is read in one repeatable-read transaction, so the sequence is the one the lists belong to: a change
     * committed after it is not in them and arrives as a delta.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SnapshotDto execute(long portId) {
        reads.beginConsistentRead();
        long sequence = changes.lastSequence(portId);
        var now = clock.instant();
        return new SnapshotDto(
                sequence,
                now,
                clockInfo.speed(),
                equipment.findAll(portId).stream().map(views::equipment).toList(),
                reads
                        .callsForSnapshot(
                                portId, now.minus(castor.state().snapshot().recentCalls()))
                        .stream()
                        .map(views::vesselCall)
                        .toList(),
                reads.inventories(portId).stream().map(views::inventory).toList(),
                reads.openGateVisits(portId),
                reads.openReconciliationItems(portId));
    }
}

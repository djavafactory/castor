package com.djavafactory.castor.core.operations.application;

import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import com.djavafactory.castor.core.operations.api.VesselCallDetailDto;
import com.djavafactory.castor.core.operations.api.VesselCallDto;
import com.djavafactory.castor.core.operations.domain.StateReadRepository;
import com.djavafactory.castor.core.operations.domain.VesselCall;
import com.djavafactory.castor.core.operations.domain.VesselStatus;
import com.djavafactory.castor.core.shared.domain.InvalidRequestException;
import com.djavafactory.castor.core.shared.domain.ResourceNotFoundException;

/** Vessel calls: the list with its filters and one call with its timeline and cargo progress. */
@ApplicationScoped
public class ReadVesselCalls {

    static final int LIST_LIMIT = 500;
    static final int TIMELINE_LIMIT = 200;

    private final StateReadRepository reads;
    private final StateViews views;
    private final InstantSource clock;

    ReadVesselCalls(StateReadRepository reads, StateViews views, InstantSource clock) {
        this.reads = reads;
        this.views = views;
        this.clock = clock;
    }

    /** Calls whose span overlaps [from, to], optionally of one status; newest arrival first. */
    @Transactional
    public List<VesselCallDto> list(long portId, String status, Instant from, Instant to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new InvalidRequestException("to is before from");
        }
        return reads.searchCalls(portId, parseStatus(status), from, to, LIST_LIMIT).stream()
                .map(views::vesselCall)
                .toList();
    }

    @Transactional
    public VesselCallDetailDto detail(long portId, UUID id) {
        VesselCall call = reads.findCall(portId, id)
                .orElseThrow(() -> new ResourceNotFoundException("No vessel call " + id + " in this port"));
        var timeline = new ArrayList<>(
                reads.lineage(portId, List.of(call.id().toString(), call.callCode()), TIMELINE_LIMIT).stream()
                        .map(views::lineage)
                        .toList());
        Collections.reverse(timeline);
        // Cargo is tied to a call by place and time: what moved at its berth while the ship was alongside
        List<VesselCallDetailDto.CargoProgressDto> cargo = call.berthAssetId() == null || call.ata() == null
                ? List.of()
                : reads
                        .cargoProgress(
                                portId,
                                call.berthAssetId(),
                                call.ata(),
                                call.atd() != null ? call.atd() : clock.instant())
                        .stream()
                        .map(p -> new VesselCallDetailDto.CargoProgressDto(
                                p.cargoFamily(), p.unit(), p.registered(), p.moved()))
                        .toList();
        return new VesselCallDetailDto(views.vesselCall(call), timeline, cargo);
    }

    private static VesselStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return VesselStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(
                    "status must be one of EXPECTED, ALONGSIDE, DEPARTED, CANCELLED: " + status);
        }
    }
}

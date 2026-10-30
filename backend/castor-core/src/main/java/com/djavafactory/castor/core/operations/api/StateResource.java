package com.djavafactory.castor.core.operations.api;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import com.djavafactory.castor.core.operations.application.OperationsPorts;
import com.djavafactory.castor.core.operations.application.ReadAssetState;
import com.djavafactory.castor.core.operations.application.ReadSnapshot;
import com.djavafactory.castor.core.operations.application.ReadVesselCalls;
import com.djavafactory.castor.core.shared.domain.InvalidRequestException;
import com.djavafactory.castor.core.shared.domain.ResourceNotFoundException;

/** The operational state of a port: the snapshot, one asset, and the vessel calls. {@code portId} is the port code. */
@Path("/api/v1/ports/{portId}")
@Produces(MediaType.APPLICATION_JSON)
public class StateResource {

    private final OperationsPorts ports;
    private final ReadSnapshot snapshot;
    private final ReadAssetState assetState;
    private final ReadVesselCalls vesselCalls;

    public StateResource(
            OperationsPorts ports, ReadSnapshot snapshot, ReadAssetState assetState, ReadVesselCalls vesselCalls) {
        this.ports = ports;
        this.snapshot = snapshot;
        this.assetState = assetState;
        this.vesselCalls = vesselCalls;
    }

    @GET
    @Path("state/snapshot")
    @RolesAllowed({"VIEWER", "OPERATOR", "EDITOR", "PUBLISHER", "ADMIN"})
    public SnapshotDto snapshot(@PathParam("portId") String portId) {
        return snapshot.execute(ports.portId(portId));
    }

    @GET
    @Path("assets/{assetId}/state")
    @RolesAllowed({"VIEWER", "OPERATOR", "EDITOR", "PUBLISHER", "ADMIN"})
    public AssetStateDto asset(@PathParam("portId") String portId, @PathParam("assetId") String assetId) {
        return assetState.execute(ports.portId(portId), uuid(assetId));
    }

    @GET
    @Path("vessel-calls")
    @RolesAllowed({"VIEWER", "OPERATOR", "EDITOR", "PUBLISHER", "ADMIN"})
    public List<VesselCallDto> calls(
            @PathParam("portId") String portId,
            @QueryParam("status") String status,
            @QueryParam("from") String from,
            @QueryParam("to") String to) {
        return vesselCalls.list(ports.portId(portId), status, instant("from", from), instant("to", to));
    }

    @GET
    @Path("vessel-calls/{id}")
    @RolesAllowed({"VIEWER", "OPERATOR", "EDITOR", "PUBLISHER", "ADMIN"})
    public VesselCallDetailDto call(@PathParam("portId") String portId, @PathParam("id") String id) {
        return vesselCalls.detail(ports.portId(portId), uuid(id));
    }

    private static UUID uuid(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw new ResourceNotFoundException("No such id: " + text);
        }
    }

    private static Instant instant(String name, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException(name + " must be a UTC time like 2026-10-30T08:00:00Z: " + text);
        }
    }
}

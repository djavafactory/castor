package com.djavafactory.castor.core.layout.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;

import com.djavafactory.castor.core.layout.application.LayoutHistory;
import com.djavafactory.castor.core.layout.domain.LayoutStatus;

/** How one asset looked in each version that holds it. */
@Path("/api/v1/assets/{assetId}/history")
@Produces(MediaType.APPLICATION_JSON)
public class AssetHistoryResource {

    public record Entry(
            UUID versionId,
            int versionNo,
            LayoutStatus status,
            String name,
            String geometry,
            String changedBy,
            Instant changedAt) {}

    private final LayoutHistory history;

    public AssetHistoryResource(LayoutHistory history) {
        this.history = history;
    }

    /** Newest version first. Drafts and discarded versions are listed for editors and above only. */
    @GET
    @RolesAllowed({"VIEWER", "OPERATOR", "EDITOR", "PUBLISHER", "ADMIN"})
    public List<Entry> history(@PathParam("assetId") UUID assetId, @Context SecurityContext security) {
        return history.assetHistory(assetId, LayoutResource.isEditor(security)).stream()
                .map(e -> new Entry(
                        e.versionId(),
                        e.versionNo(),
                        e.status(),
                        e.name(),
                        e.geometrySummary(),
                        e.changedBy(),
                        e.changedAt()))
                .toList();
    }
}

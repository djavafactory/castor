package com.djavafactory.castor.core.port.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.fasterxml.jackson.annotation.JsonRawValue;

import com.djavafactory.castor.core.port.application.GetControlPoints;
import com.djavafactory.castor.core.port.application.GetPort;
import com.djavafactory.castor.core.port.application.GetPort.PortDetail;
import com.djavafactory.castor.core.port.application.GetReferenceFeatures;
import com.djavafactory.castor.core.port.application.ListPorts;
import com.djavafactory.castor.core.port.domain.DataSourceInfo;
import com.djavafactory.castor.core.shared.api.CachedJson;

@Path("/api/v1/ports")
@Produces(MediaType.APPLICATION_JSON)
public class PortResource {

    public record LonLatResponse(double lon, double lat) {}

    /** Geometries are GeoJSON written exactly as the database produced it. */
    public record PortResponse(
            String code,
            String name,
            String city,
            String timeZone,
            String utcOffset,
            LonLatResponse origin,
            int measureSrid,
            double frameRotDeg,
            @JsonRawValue String extent,
            @JsonRawValue String landExtent,
            @JsonRawValue String quayLine,
            boolean synthetic,
            UUID publishedLayoutVersionId,
            int berthCount,
            String attribution,
            List<DataSourceInfo> dataSources) {}

    private final ListPorts listPorts;
    private final GetPort getPort;
    private final GetControlPoints getControlPoints;
    private final GetReferenceFeatures getReferenceFeatures;
    private final CachedJson cache;

    public PortResource(
            ListPorts listPorts,
            GetPort getPort,
            GetControlPoints getControlPoints,
            GetReferenceFeatures getReferenceFeatures,
            CachedJson cache) {
        this.listPorts = listPorts;
        this.getPort = getPort;
        this.getControlPoints = getControlPoints;
        this.getReferenceFeatures = getReferenceFeatures;
        this.cache = cache;
    }

    @GET
    @RolesAllowed({"VIEWER", "OPERATOR", "EDITOR", "PUBLISHER", "ADMIN"})
    public Response list(@HeaderParam("If-None-Match") String ifNoneMatch) {
        return cache.of(ifNoneMatch, listPorts.execute());
    }

    @GET
    @Path("{portCode}")
    @RolesAllowed({"VIEWER", "OPERATOR", "EDITOR", "PUBLISHER", "ADMIN"})
    public Response get(@PathParam("portCode") String portCode, @HeaderParam("If-None-Match") String ifNoneMatch) {
        return cache.of(ifNoneMatch, toResponse(getPort.execute(portCode)));
    }

    @GET
    @Path("{portCode}/control-points")
    @RolesAllowed({"VIEWER", "OPERATOR", "EDITOR", "PUBLISHER", "ADMIN"})
    public Response controlPoints(
            @PathParam("portCode") String portCode, @HeaderParam("If-None-Match") String ifNoneMatch) {
        return cache.raw(ifNoneMatch, getControlPoints.execute(portCode));
    }

    @GET
    @Path("{portCode}/reference-features")
    @RolesAllowed({"VIEWER", "OPERATOR", "EDITOR", "PUBLISHER", "ADMIN"})
    public Response referenceFeatures(
            @PathParam("portCode") String portCode,
            @QueryParam("kind") String kinds,
            @QueryParam("bbox") String bbox,
            @HeaderParam("If-None-Match") String ifNoneMatch) {
        return cache.raw(ifNoneMatch, getReferenceFeatures.execute(portCode, kinds, bbox));
    }

    private static PortResponse toResponse(PortDetail detail) {
        var port = detail.port();
        return new PortResponse(
                port.code(),
                port.name(),
                port.city(),
                port.timeZone(),
                port.utcOffset(Instant.now()),
                new LonLatResponse(port.origin().lon(), port.origin().lat()),
                port.measureSrid(),
                port.frameRotDeg(),
                port.extent(),
                port.landExtent(),
                port.quayLine(),
                port.synthetic(),
                port.publishedLayoutVersionId(),
                detail.berthCount(),
                detail.attribution(),
                detail.dataSources());
    }
}

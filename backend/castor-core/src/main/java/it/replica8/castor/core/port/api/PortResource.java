package it.replica8.castor.core.port.api;

import java.util.List;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.replica8.castor.core.port.application.GetPort;
import it.replica8.castor.core.port.application.ListReferenceFeatures;
import it.replica8.castor.core.port.domain.Port;
import it.replica8.castor.core.port.domain.ReferenceFeature;

@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"VIEWER", "EDITOR", "PUBLISHER", "ADMIN"})
public class PortResource {

    public record LonLatResponse(double lon, double lat) {}

    public record PortResponse(
            String code, String name, boolean synthetic, LonLatResponse origin, double rotDeg, int measureSrid) {}

    public record FeatureProperties(
            String kind, String osmRef, String name, Double heightM, Double areaM2, String source, String accuracy) {}

    public record Feature(String type, String id, JsonNode geometry, FeatureProperties properties) {}

    public record FeatureCollection(String type, List<Feature> features) {}

    private final GetPort getPort;
    private final ListReferenceFeatures listReferenceFeatures;
    private final ObjectMapper json;

    public PortResource(GetPort getPort, ListReferenceFeatures listReferenceFeatures, ObjectMapper json) {
        this.getPort = getPort;
        this.listReferenceFeatures = listReferenceFeatures;
        this.json = json;
    }

    /** The configured port: what the app shell shows in its header. */
    @GET
    @Path("/port")
    public PortResponse configuredPort() {
        return toResponse(getPort.configured());
    }

    /** The port record, including the quay frame every 2D and 3D layer converts positions with. */
    @GET
    @Path("/ports/{portCode}")
    public PortResponse port(@PathParam("portCode") String portCode) {
        return toResponse(getPort.execute(portCode));
    }

    /** Reference features as GeoJSON. Read-only: there is no write endpoint for them. */
    @GET
    @Path("/ports/{portCode}/reference-features")
    public FeatureCollection referenceFeatures(
            @PathParam("portCode") String portCode, @QueryParam("kind") @DefaultValue("BUILDING") String kind) {
        List<Feature> features = listReferenceFeatures.execute(portCode, kind).stream()
                .map(this::toFeature)
                .toList();
        return new FeatureCollection("FeatureCollection", features);
    }

    private static PortResponse toResponse(Port p) {
        return new PortResponse(
                p.code(),
                p.name(),
                p.synthetic(),
                new LonLatResponse(p.originLon(), p.originLat()),
                p.rotDeg(),
                p.measureSrid());
    }

    private Feature toFeature(ReferenceFeature f) {
        try {
            return new Feature(
                    "Feature",
                    f.osmRef(),
                    json.readTree(f.geometryGeoJson()),
                    new FeatureProperties(
                            f.kind(), f.osmRef(), f.name(), f.heightM(), f.areaM2(), f.source(), f.accuracy()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored geometry of " + f.osmRef() + " is not GeoJSON", e);
        }
    }
}

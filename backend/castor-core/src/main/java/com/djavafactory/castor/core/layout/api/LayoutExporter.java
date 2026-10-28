package com.djavafactory.castor.core.layout.api;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.djavafactory.castor.core.geometry.application.GeoJsonMapper;
import com.djavafactory.castor.core.layout.application.GetLayout;
import com.djavafactory.castor.core.layout.domain.LayoutVersion;
import com.djavafactory.castor.core.port.domain.DataSourceInfo;
import com.djavafactory.castor.core.port.domain.PortRepository;
import com.djavafactory.castor.core.shared.domain.ResourceNotFoundException;

/**
 * A layout version as a standard GeoJSON FeatureCollection (DESIGNER-10): CRS84 [lon, lat] to 7 decimals, the port frame
 * and the version in a {@code castor} member, and every feature with its provenance. Features whose data source may not
 * be stored (Google Maps) are left out, so nothing derived from such a source ever leaves the system.
 */
@ApplicationScoped
public class LayoutExporter {

    public static final int SCHEMA_VERSION = 1;
    public static final String CRS84 = "urn:ogc:def:crs:OGC:1.3:CRS84";

    /** The file to send: its name and content, and how many features it holds. */
    public record Export(String filename, ObjectNode body, int featureCount) {}

    private final GetLayout getLayout;
    private final PortRepository ports;
    private final LayoutFeatureMapper mapper;
    private final GeoJsonMapper geo;
    private final ObjectMapper json;

    public LayoutExporter(
            GetLayout getLayout,
            PortRepository ports,
            LayoutFeatureMapper mapper,
            GeoJsonMapper geo,
            ObjectMapper json) {
        this.getLayout = getLayout;
        this.ports = ports;
        this.mapper = mapper;
        this.geo = geo;
        this.json = json;
    }

    /** The visible version is checked by the caller; {@code now} and {@code user} go into the provenance of the file. */
    public Export export(LayoutVersion version, UUID layoutId, String user, Instant now) {
        var port = ports.findByCode(version.portCode())
                .orElseThrow(() -> new ResourceNotFoundException("No port " + version.portCode()));
        var blocked = ports.findDataSources().stream()
                .filter(s -> !s.mayPersist())
                .map(DataSourceInfo::code)
                .collect(Collectors.toSet());
        var view = getLayout.byId(layoutId).view();
        var features = view.assets().stream()
                .filter(a -> !blocked.contains(a.provenance().source()))
                .map(a -> mapper.feature(a, view.measures().get(a.id())))
                .toList();

        ObjectNode out = geo.featureCollection(features);
        // type first, then the members GIS tools read, then the features
        ObjectNode ordered = json.createObjectNode();
        ordered.put("type", "FeatureCollection");
        ordered.put("name", "castor-" + port.code() + "-v" + version.versionNo());
        ordered.putObject("crs").put("type", "name").putObject("properties").put("name", CRS84);
        ObjectNode castor = ordered.putObject("castor");
        castor.put("schemaVersion", SCHEMA_VERSION);
        castor.put("port", port.code());
        castor.set(
                "origin",
                json.createArrayNode()
                        .add(port.origin().lon())
                        .add(port.origin().lat()));
        castor.put("frameRotDeg", port.frameRotDeg());
        castor.put("measureSrid", port.measureSrid());
        castor.put("versionNo", version.versionNo());
        castor.put("exportedAt", now.toString());
        castor.put("exportedBy", user);
        ordered.set("features", out.get("features"));

        String day = LocalDate.ofInstant(now, ZoneId.of(port.timeZone())).toString();
        return new Export(
                "castor-" + port.code() + "-v" + version.versionNo() + "-" + day + ".geojson",
                ordered,
                features.size());
    }
}

package com.djavafactory.castor.core.layout.application;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import org.locationtech.jts.geom.LineString;

import com.djavafactory.castor.core.geometry.domain.QuayFrame;
import com.djavafactory.castor.core.layout.domain.AssetMeasures;
import com.djavafactory.castor.core.layout.domain.LayoutAsset;
import com.djavafactory.castor.core.layout.domain.LayoutRepository;
import com.djavafactory.castor.core.layout.domain.LayoutVersion;
import com.djavafactory.castor.core.layout.domain.LayoutView;
import com.djavafactory.castor.core.port.domain.Port;
import com.djavafactory.castor.core.port.domain.PortRepository;
import com.djavafactory.castor.core.shared.domain.ResourceNotFoundException;

/**
 * Use cases that read a layout version: the published one, any by id, the current draft and a single asset. The
 * measurements the repository computes in the measurement CRS are completed here with the local position of the
 * anchor in the quay frame and the chainage of berths.
 */
@ApplicationScoped
public class GetLayout {

    /** @param etag changes whenever anything in the answer changes; for one asset it is the row version */
    public record Result(LayoutView view, String etag) {}

    private final LayoutRepository layouts;
    private final PortRepository ports;

    public GetLayout(LayoutRepository layouts, PortRepository ports) {
        this.layouts = layouts;
        this.ports = ports;
    }

    /** The published version of the port. */
    public Result current(String portCode) {
        var version = layouts.findPublished(portCode.toLowerCase())
                .orElseThrow(() -> new ResourceNotFoundException("Port '" + portCode + "' has no published layout"));
        return whole(version);
    }

    /** Any version, whatever its status. */
    public Result byId(UUID layoutId) {
        return whole(requireVersion(layoutId));
    }

    /** The open draft of the port, or 404. */
    public Result draft(String portCode) {
        var version = layouts.findDraft(portCode.toLowerCase())
                .orElseThrow(() -> new ResourceNotFoundException("Port '" + portCode + "' has no draft"));
        return whole(version);
    }

    /** One asset of one version; the etag is its row version. */
    public Result asset(UUID layoutId, UUID assetId) {
        var version = requireVersion(layoutId);
        var view = layouts.loadAsset(version, assetId)
                .orElseThrow(() -> new ResourceNotFoundException("Layout " + layoutId + " has no asset " + assetId));
        return new Result(complete(view), String.valueOf(view.assets().get(0).rowVersion()));
    }

    private LayoutVersion requireVersion(UUID layoutId) {
        return layouts.findVersion(layoutId)
                .orElseThrow(() -> new ResourceNotFoundException("No layout version " + layoutId));
    }

    private Result whole(LayoutVersion version) {
        var view = complete(layouts.load(version));
        return new Result(view, view.etagSource());
    }

    private LayoutView complete(LayoutView view) {
        Port port = ports.findByCode(view.version().portCode())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No port " + view.version().portCode()));
        var frame = new QuayFrame(port.origin().lon(), port.origin().lat(), port.frameRotDeg());
        Map<UUID, AssetMeasures> out = new HashMap<>();
        for (LayoutAsset a : view.assets()) {
            var m = view.measures().get(a.id());
            var local = frame.toLocal(a.anchor());
            AssetMeasures.Chainage chainage = null;
            if ("BERTH".equals(a.typeCode()) && a.geom() instanceof LineString line) {
                var start = line.getCoordinateN(0);
                var end = line.getCoordinateN(line.getNumPoints() - 1);
                chainage = new AssetMeasures.Chainage(
                        frame.toLocal(start.x, start.y).x(),
                        frame.toLocal(end.x, end.y).x());
            }
            out.put(
                    a.id(),
                    new AssetMeasures(
                            m == null ? null : m.areaM2(),
                            m == null ? null : m.perimeterM(),
                            m == null ? null : m.lengthM(),
                            local,
                            chainage));
        }
        return new LayoutView(view.version(), view.assets(), out, view.etagSource());
    }
}

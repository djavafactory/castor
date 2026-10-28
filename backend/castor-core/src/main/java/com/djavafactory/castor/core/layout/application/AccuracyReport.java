package com.djavafactory.castor.core.layout.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.core.geometry.domain.LonLat;
import com.djavafactory.castor.core.geometry.domain.Measurements;
import com.djavafactory.castor.core.geometry.domain.QuayFrame;
import com.djavafactory.castor.core.layout.domain.LayoutAsset;
import com.djavafactory.castor.core.layout.domain.LayoutRepository;
import com.djavafactory.castor.core.port.domain.AccuracyClass;
import com.djavafactory.castor.core.port.domain.ControlPoint;
import com.djavafactory.castor.core.port.domain.Port;
import com.djavafactory.castor.core.port.domain.PortRepository;
import com.djavafactory.castor.core.shared.domain.ResourceNotFoundException;

/**
 * DESIGNER-11 part 1: how well the stored coordinates match the port's control points, and how much of the layout is
 * only approximate. Residuals and the projection round trip are measured in the port's metric CRS (PRD 19.1), never
 * on raw degrees.
 */
@ApplicationScoped
public class AccuracyReport {

    /** One control point: the residual against its expected local position, and the projection round trip. */
    public record ControlPointResult(String code, String name, Double residualM, double roundTripMm) {}

    public record Result(
            Instant ranAt,
            List<ControlPointResult> controlPoints,
            double maxResidualM,
            double maxRoundTripMm,
            Map<AccuracyClass, Integer> countsByAccuracy,
            int approximateCount) {}

    public static final String NOTE = "The acceptance tolerance must be agreed with the customer; satellite tracing "
            + "cannot give sub-metre accuracy.";

    private final LayoutRepository layouts;
    private final PortRepository ports;
    private final Measurements measurements;

    public AccuracyReport(LayoutRepository layouts, PortRepository ports, Measurements measurements) {
        this.layouts = layouts;
        this.ports = ports;
        this.measurements = measurements;
    }

    public Result execute(UUID layoutId) {
        var version = layouts.findVersion(layoutId)
                .orElseThrow(() -> new ResourceNotFoundException("No layout version " + layoutId));
        Port port = ports.findByCode(version.portCode())
                .orElseThrow(() -> new ResourceNotFoundException("No port " + version.portCode()));
        var frame = new QuayFrame(port.origin().lon(), port.origin().lat(), port.frameRotDeg());

        List<ControlPointResult> controlPoints = new ArrayList<>();
        double maxResidual = 0;
        double maxRoundTrip = 0;
        for (ControlPoint cp : ports.controlPoints(port.id())) {
            LonLat expected = frame.toLonLat(cp.expectedLocalX(), cp.expectedLocalY());
            double residual = measurements.distanceM(expected, cp.position(), port.measureSrid());
            LonLat roundTrip = measurements.roundTrip(cp.position(), port.measureSrid());
            double roundTripMm = measurements.distanceM(cp.position(), roundTrip, port.measureSrid()) * 1000;
            controlPoints.add(new ControlPointResult(cp.code(), cp.name(), residual, roundTripMm));
            maxResidual = Math.max(maxResidual, residual);
            maxRoundTrip = Math.max(maxRoundTrip, roundTripMm);
        }

        Map<AccuracyClass, Integer> counts = new EnumMap<>(AccuracyClass.class);
        for (AccuracyClass a : AccuracyClass.values()) {
            counts.put(a, 0);
        }
        for (LayoutAsset asset : layouts.load(version).assets()) {
            counts.merge(AccuracyClass.valueOf(asset.provenance().accuracy()), 1, Integer::sum);
        }

        return new Result(
                Instant.now(),
                controlPoints,
                maxResidual,
                maxRoundTrip,
                counts,
                counts.getOrDefault(AccuracyClass.APPROXIMATE, 0));
    }
}

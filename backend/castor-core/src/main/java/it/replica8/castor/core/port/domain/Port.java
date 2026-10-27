package it.replica8.castor.core.port.domain;

/**
 * A port: its quay frame (origin and rotation of local +X) and the CRS its measurements use.
 *
 * @param rotDeg angle of local +X, degrees counter-clockwise from east
 */
public record Port(
        String code,
        String name,
        boolean synthetic,
        double originLon,
        double originLat,
        double rotDeg,
        int measureSrid) {}

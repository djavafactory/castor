package com.djavafactory.castor.core.port.domain;

import com.djavafactory.castor.core.shared.domain.InvalidRequestException;

/** A WGS84 box, GeoJSON axis order. */
public record BoundingBox(double minLon, double minLat, double maxLon, double maxLat) {

    public BoundingBox {
        if (minLon < -180 || maxLon > 180 || minLat < -90 || maxLat > 90 || minLon > maxLon || minLat > maxLat) {
            throw new InvalidRequestException("bbox must be minLon,minLat,maxLon,maxLat within WGS84 and not inverted");
        }
    }

    /** Parses "minLon,minLat,maxLon,maxLat". */
    public static BoundingBox parse(String text) {
        String[] parts = text.split(",", -1);
        if (parts.length != 4) {
            throw new InvalidRequestException("bbox must be minLon,minLat,maxLon,maxLat");
        }
        try {
            return new BoundingBox(
                    Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim()),
                    Double.parseDouble(parts[2].trim()),
                    Double.parseDouble(parts[3].trim()));
        } catch (NumberFormatException e) {
            throw new InvalidRequestException("bbox must be four numbers");
        }
    }
}

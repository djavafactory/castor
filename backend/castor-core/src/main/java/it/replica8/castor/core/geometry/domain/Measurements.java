package it.replica8.castor.core.geometry.domain;

import java.util.List;

/**
 * Lengths, areas, perimeters and distances in metres. Implementations measure in the port's measurement CRS
 * (EPSG:3380 for Westports), never on raw degrees.
 */
public interface Measurements {

    /** The measurement CRS of Westports (SETUP-03); each port carries its own as measure_srid. */
    int DEFAULT_MEASURE_SRID = 3380;

    double lengthM(List<LonLat> line, int measureSrid);

    /** Ring is open (first point not repeated). */
    double areaM2(List<LonLat> ring, int measureSrid);

    /** Ring is open (first point not repeated). */
    double perimeterM(List<LonLat> ring, int measureSrid);

    double distanceM(LonLat a, LonLat b, int measureSrid);

    /** 4326 to the measurement CRS and back; used to prove the projection round trip. */
    LonLat roundTrip(LonLat p, int measureSrid);
}

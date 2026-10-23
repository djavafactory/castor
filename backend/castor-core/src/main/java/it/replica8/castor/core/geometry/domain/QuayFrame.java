package it.replica8.castor.core.geometry.domain;

/**
 * The quay frame of a port: a local tangent plane at (lon0, lat0), turned by rotDeg so local +X runs along the quay.
 * The formulas are SETUP-03; the frontend lib/geo module uses the same ones and the shared fixtures prove it.
 *
 * @param rotDeg angle of local +X, degrees counter-clockwise from east
 */
public record QuayFrame(double lon0, double lat0, double rotDeg) {

    private static final double RAD = Math.PI / 180;

    /** Metres per degree of latitude at the given latitude. */
    public static double metresPerDegreeLat(double latDeg) {
        return 111132.92 - 559.82 * Math.cos(2 * latDeg * RAD) + 1.175 * Math.cos(4 * latDeg * RAD);
    }

    /** Metres per degree of longitude at the given latitude. */
    public static double metresPerDegreeLon(double latDeg) {
        return 111412.84 * Math.cos(latDeg * RAD) - 93.5 * Math.cos(3 * latDeg * RAD);
    }

    /** WGS84 to local metres. */
    public LocalPoint toLocal(double lon, double lat) {
        double e = (lon - lon0) * metresPerDegreeLon(lat0);
        double n = (lat - lat0) * metresPerDegreeLat(lat0);
        double a = rotDeg * RAD;
        double c = Math.cos(a);
        double s = Math.sin(a);
        return new LocalPoint(e * c + n * s, -e * s + n * c);
    }

    public LocalPoint toLocal(LonLat p) {
        return toLocal(p.lon(), p.lat());
    }

    /** Local metres to WGS84. */
    public LonLat toLonLat(double x, double y) {
        double a = rotDeg * RAD;
        double c = Math.cos(a);
        double s = Math.sin(a);
        return new LonLat(
                lon0 + (x * c - y * s) / metresPerDegreeLon(lat0), lat0 + (x * s + y * c) / metresPerDegreeLat(lat0));
    }

    public LonLat toLonLat(LocalPoint p) {
        return toLonLat(p.x(), p.y());
    }

    /** True heading (degrees clockwise from north) to rotation (degrees counter-clockwise from local +X, -180..180). */
    public double headingToRotation(double headingDeg) {
        double r = mod360(90 - headingDeg - rotDeg);
        return r > 180 ? r - 360 : r;
    }

    /** Rotation (degrees counter-clockwise from local +X) to true heading, 0..360. */
    public double rotationToHeading(double rot) {
        return mod360(90 - rot - rotDeg);
    }

    private static double mod360(double deg) {
        double r = deg % 360;
        return r < 0 ? r + 360 : r;
    }
}

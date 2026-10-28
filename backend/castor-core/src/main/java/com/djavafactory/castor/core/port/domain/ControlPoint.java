package com.djavafactory.castor.core.port.domain;

import com.djavafactory.castor.core.geometry.domain.LonLat;

/**
 * A surveyed or approximate reference point of a port (DESIGNER-11): where it really is in WGS84 and where it is
 * expected to fall in the quay frame, so the accuracy report can compare the two.
 */
public record ControlPoint(String code, String name, LonLat position, double expectedLocalX, double expectedLocalY) {}

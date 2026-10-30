package com.djavafactory.castor.core.operations.domain;

public interface PortExtents {

    /** True when the WGS84 point lies inside the port extent (its border counts as inside). */
    boolean covers(long portId, double lon, double lat);
}

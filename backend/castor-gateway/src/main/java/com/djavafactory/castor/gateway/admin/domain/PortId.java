package com.djavafactory.castor.gateway.admin.domain;

import java.util.regex.Pattern;

/** The port code of an admin URL, in the form ingest.integration_source accepts. */
public final class PortId {

    private static final Pattern FORMAT = Pattern.compile("[a-z0-9-]{2,40}");

    private PortId() {}

    /**
     * @throws AdminException INVALID when the text is not a port code
     */
    public static String require(String portId) {
        if (portId == null || !FORMAT.matcher(portId).matches()) {
            throw AdminException.invalid("INVALID_PORT", "The port id must be 2 to 40 characters of a-z, 0-9 and -");
        }
        return portId;
    }
}

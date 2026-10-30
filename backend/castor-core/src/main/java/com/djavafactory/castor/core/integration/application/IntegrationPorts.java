package com.djavafactory.castor.core.integration.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import com.djavafactory.castor.core.port.domain.PortRepository;
import com.djavafactory.castor.core.shared.domain.ResourceNotFoundException;

/** Checks that the port of an integrations URL exists, before the gateway is asked anything about it. */
@ApplicationScoped
public class IntegrationPorts {

    private final PortRepository ports;

    public IntegrationPorts(PortRepository ports) {
        this.ports = ports;
    }

    /** The port code in the form the gateway uses (lower case). */
    @Transactional
    public String require(String portId) {
        String code = portId.toLowerCase();
        ports.findByCode(code).orElseThrow(() -> new ResourceNotFoundException("No port with code '" + portId + "'"));
        return code;
    }
}

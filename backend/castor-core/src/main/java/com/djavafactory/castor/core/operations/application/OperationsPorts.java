package com.djavafactory.castor.core.operations.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import com.djavafactory.castor.core.port.domain.PortRepository;
import com.djavafactory.castor.core.shared.domain.ResourceNotFoundException;

/** Turns the port code of an operations URL into the port id every ops table is keyed by. */
@ApplicationScoped
public class OperationsPorts {

    private final PortRepository ports;

    OperationsPorts(PortRepository ports) {
        this.ports = ports;
    }

    /** The id of the port; 404 when the code is unknown. Codes are case-insensitive. */
    @Transactional
    public long portId(String code) {
        return ports.findByCode(code.toLowerCase())
                .orElseThrow(() -> new ResourceNotFoundException("No port with code '" + code + "'"))
                .id();
    }
}

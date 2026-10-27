package it.replica8.castor.core.port.application;

import jakarta.enterprise.context.ApplicationScoped;

import it.replica8.castor.core.port.domain.Port;
import it.replica8.castor.core.port.domain.PortRepository;
import it.replica8.castor.core.shared.domain.ResourceNotFoundException;
import it.replica8.castor.core.shared.infrastructure.CastorConfig;

/** Use case: the record of one port. */
@ApplicationScoped
public class GetPort {

    private final PortRepository ports;
    private final CastorConfig config;

    public GetPort(PortRepository ports, CastorConfig config) {
        this.ports = ports;
        this.config = config;
    }

    /** The port this installation is set up for (castor.port-code). */
    public Port configured() {
        return execute(config.portCode());
    }

    public Port execute(String code) {
        return ports.findByCode(code).orElseThrow(() -> new ResourceNotFoundException("Unknown port " + code));
    }
}

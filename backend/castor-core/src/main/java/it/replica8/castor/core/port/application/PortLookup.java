package it.replica8.castor.core.port.application;

import it.replica8.castor.core.port.domain.Port;
import it.replica8.castor.core.port.domain.PortRepository;
import it.replica8.castor.core.shared.domain.ResourceNotFoundException;

/** Finds a port by code or reports 404. Port codes are case-insensitive in requests. */
final class PortLookup {

    static Port require(PortRepository repository, String code) {
        return repository
                .findByCode(code.toLowerCase())
                .orElseThrow(() -> new ResourceNotFoundException("No port with code '" + code + "'"));
    }

    private PortLookup() {}
}

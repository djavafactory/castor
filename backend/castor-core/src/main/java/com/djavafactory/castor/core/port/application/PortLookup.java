package com.djavafactory.castor.core.port.application;

import com.djavafactory.castor.core.port.domain.Port;
import com.djavafactory.castor.core.port.domain.PortRepository;
import com.djavafactory.castor.core.shared.domain.ResourceNotFoundException;

/** Finds a port by code or reports 404. Port codes are case-insensitive in requests. */
final class PortLookup {

    static Port require(PortRepository repository, String code) {
        return repository
                .findByCode(code.toLowerCase())
                .orElseThrow(() -> new ResourceNotFoundException("No port with code '" + code + "'"));
    }

    private PortLookup() {}
}

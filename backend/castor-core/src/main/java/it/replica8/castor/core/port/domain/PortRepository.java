package it.replica8.castor.core.port.domain;

import java.util.Optional;

public interface PortRepository {

    Optional<Port> findByCode(String code);
}

package com.djavafactory.castor.gateway.adapters.domain;

import com.djavafactory.castor.contracts.Quality;

/** The quality a source's events carry: configured per source and port, not read from the message. */
public interface SourceQualities {

    Quality of(String portId, String source);
}

package com.djavafactory.castor.core.operations.application;

import com.djavafactory.castor.core.operations.domain.StateChange;

/** Where the change feed hands what it reads: in sequence order, once per change. */
public interface StateChangeSink {

    /** The feed starts after this sequence of the port: earlier changes are not delivered. */
    void baseline(long portId, long sequence);

    void accept(StateChange change);
}

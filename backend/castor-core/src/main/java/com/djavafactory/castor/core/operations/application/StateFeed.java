package com.djavafactory.castor.core.operations.application;

/** The change feed of this instance, as far as the layers above it may steer it. */
public interface StateFeed {

    /** Forgets what was delivered and delivers only what is committed from now on (after the data was reset). */
    void rebase();
}

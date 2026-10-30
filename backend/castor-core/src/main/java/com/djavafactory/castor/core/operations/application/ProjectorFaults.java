package com.djavafactory.castor.core.operations.application;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.contracts.CanonicalEvent;

/**
 * Fail points of the projector. Production does nothing here; tests replace this bean to break the consumer at the two
 * moments that matter: inside the transaction of an event (the retry and dead letter path) and after its commit but
 * before its offset is acknowledged (the crash that must not apply an event twice).
 */
@ApplicationScoped
public class ProjectorFaults {

    /** Inside the transaction, after the event was stored and before its handler runs. */
    public void beforeHandle(CanonicalEvent event) {}

    /** After the transaction committed and before the offset is acknowledged. */
    public void afterCommit(IncomingRecord record) {}
}

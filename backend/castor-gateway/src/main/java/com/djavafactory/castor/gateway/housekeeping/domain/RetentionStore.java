package com.djavafactory.castor.gateway.housekeeping.domain;

import java.time.Instant;

/** What is kept and for how long: the removal side of the inbox, outbox and quarantine tables. */
public interface RetentionStore {

    /**
     * @param outbox published outbox rows deleted
     * @param raw raw payloads emptied (NORMALIZED records, and the QUARANTINED records whose entry expired)
     * @param quarantine quarantine entries deleted
     */
    record Purged(int outbox, int raw, int quarantine) {}

    /**
     * In one transaction: deletes outbox rows published before {@code publishedBefore}, empties the raw payload of
     * NORMALIZED inbox rows normalized before {@code normalizedBefore}, and deletes quarantine entries created before
     * {@code quarantinedBefore} together with the raw payload of their record. Inbox rows themselves are never deleted:
     * their unique key is what recognises a repeat.
     */
    Purged purge(Instant publishedBefore, Instant normalizedBefore, Instant quarantinedBefore);
}

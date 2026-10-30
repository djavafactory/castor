package com.djavafactory.castor.gateway.housekeeping.application;

import java.time.Instant;
import java.time.InstantSource;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

import com.djavafactory.castor.gateway.housekeeping.domain.RetentionStore;
import com.djavafactory.castor.gateway.shared.infrastructure.CastorConfig;

/**
 * Housekeeping: published outbox rows and the raw payloads of NORMALIZED records go after 7 days, quarantine entries
 * after 30 (castor.retention.*). Cut-off times come from the injected clock.
 */
@ApplicationScoped
public class RunHousekeeping {

    private static final Logger LOG = Logger.getLogger(RunHousekeeping.class);

    private final RetentionStore store;
    private final InstantSource clock;
    private final CastorConfig.Retention retention;

    public RunHousekeeping(RetentionStore store, InstantSource clock, CastorConfig castor) {
        this.store = store;
        this.clock = clock;
        this.retention = castor.retention();
    }

    public RetentionStore.Purged runOnce() {
        Instant now = clock.instant();
        RetentionStore.Purged purged = store.purge(
                now.minus(retention.outbox()), now.minus(retention.raw()), now.minus(retention.quarantine()));
        if (purged.outbox() + purged.raw() + purged.quarantine() > 0) {
            LOG.infof(
                    "Housekeeping: %d published outbox rows deleted, %d raw payloads emptied, %d quarantine entries deleted",
                    purged.outbox(), purged.raw(), purged.quarantine());
        }
        return purged;
    }
}

package com.djavafactory.castor.core.operations.infrastructure;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import org.jboss.logging.Logger;

import com.djavafactory.castor.core.operations.application.StreamHub;
import com.djavafactory.castor.core.operations.domain.StateChangeRepository;

/** Runs this instance's change feed into its WebSocket hub, and the hub's heartbeat and session checks. */
@ApplicationScoped
public class StreamFeedLifecycle {

    private static final Logger LOG = Logger.getLogger(StreamFeedLifecycle.class);

    private final StateChangeFeed feed;
    private final StreamHub hub;

    StreamFeedLifecycle(DataSource dataSource, StateChangeRepository changes, StreamHub hub) {
        this.hub = hub;
        this.feed = new StateChangeFeed(dataSource, changes, hub);
    }

    void start(@Observes StartupEvent event) {
        try {
            feed.start();
        } catch (RuntimeException e) {
            // no database at start: the feed is not what stops the service, readiness reports the database
            LOG.warn("State change feed not started: " + e.getMessage());
        }
    }

    void stop(@Observes ShutdownEvent event) {
        feed.close();
    }

    /** For tests that reset the data: deliver only what is written from now on. */
    public void rebase() {
        feed.rebase();
    }

    @Scheduled(every = "{castor.state.stream.heartbeat}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void heartbeat() {
        hub.heartbeat();
        hub.closeUnauthorised();
    }
}

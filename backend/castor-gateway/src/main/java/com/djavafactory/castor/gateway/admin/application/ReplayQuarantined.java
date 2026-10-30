package com.djavafactory.castor.gateway.admin.application;

import java.time.InstantSource;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

import com.djavafactory.castor.gateway.admin.domain.AdminException;
import com.djavafactory.castor.gateway.admin.domain.EvidenceStore;
import com.djavafactory.castor.gateway.admin.domain.PortId;
import com.djavafactory.castor.gateway.shared.domain.Uuid7;

/**
 * Puts a quarantined record through the pipeline again. The replay is a new inbox row (same source, sourceEventId and
 * raw payload, {@code replay_of} the original) that the normalizer takes like any received record: if it is still broken
 * it is quarantined again as a second, linked attempt. The original row and its quarantine entry are never touched.
 */
@ApplicationScoped
public class ReplayQuarantined {

    private static final Logger LOG = Logger.getLogger(ReplayQuarantined.class);

    /** The new inbox row and the original it repeats. */
    public record Replay(UUID receptionId, UUID replayOf) {}

    private final EvidenceStore store;
    private final InstantSource clock;
    private final Uuid7.Generator ids;

    public ReplayQuarantined(EvidenceStore store, InstantSource clock, Uuid7.Generator ids) {
        this.store = store;
        this.clock = clock;
        this.ids = ids;
    }

    public Replay replay(String portId, UUID receptionId) {
        var now = clock.instant();
        EvidenceStore.Replayed result = store.replay(PortId.require(portId), receptionId, ids.next(now), now);
        return switch (result) {
            case EvidenceStore.Replayed.Created created -> {
                LOG.infof("Replay of %s stored as %s", created.replayOf(), created.receptionId());
                yield new Replay(created.receptionId(), created.replayOf());
            }
            case EvidenceStore.Replayed.NotQuarantined n ->
                throw AdminException.notFound("NOT_IN_QUARANTINE", "No quarantined record " + receptionId);
            case EvidenceStore.Replayed.RawExpired r ->
                throw AdminException.conflict(
                        "RAW_EXPIRED", "The payload of this record was removed after its retention: nothing to replay");
            case EvidenceStore.Replayed.Pending p ->
                throw AdminException.conflict(
                        "REPLAY_PENDING", "Replay " + p.receptionId() + " of this record has not been processed yet");
        };
    }
}

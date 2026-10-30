package com.djavafactory.castor.gateway.normalize.application;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import io.micrometer.core.instrument.MeterRegistry;
import org.jboss.logging.Logger;

import com.djavafactory.castor.contracts.CanonicalEvent;
import com.djavafactory.castor.gateway.normalize.domain.Decision;
import com.djavafactory.castor.gateway.normalize.domain.EventIds;
import com.djavafactory.castor.gateway.normalize.domain.InboxRecord;
import com.djavafactory.castor.gateway.normalize.domain.NormalizationStore;
import com.djavafactory.castor.gateway.normalize.domain.Rejection;
import com.djavafactory.castor.gateway.normalize.domain.SourceAdapter;
import com.djavafactory.castor.gateway.shared.domain.FailPoint;
import com.djavafactory.castor.gateway.shared.domain.FailPoints;
import com.djavafactory.castor.gateway.shared.infrastructure.CastorConfig;

/**
 * The normalizer worker. One run takes a batch of RECEIVED inbox rows and, per row, asks the {@link SourceAdapter} of
 * its source for the canonical events. The store writes the outbox rows and marks the row NORMALIZED in one
 * transaction; a rejected row is quarantined in the same way. A source without an adapter, and an adapter that fails
 * with an error of its own, quarantine the record too, so one bad record never blocks the ones behind it.
 */
@ApplicationScoped
public class NormalizeInbox {

    private static final Logger LOG = Logger.getLogger(NormalizeInbox.class);
    private static final int MAX_DETAIL = 2000;

    private final NormalizationStore store;
    private final Instance<SourceAdapter> adapterBeans;
    private final FailPoints failPoints;
    private final MeterRegistry meters;
    private final int batchSize;
    private volatile Map<String, SourceAdapter> adapters;

    public NormalizeInbox(
            NormalizationStore store,
            Instance<SourceAdapter> adapterBeans,
            FailPoints failPoints,
            MeterRegistry meters,
            CastorConfig castor) {
        this.store = store;
        this.adapterBeans = adapterBeans;
        this.failPoints = failPoints;
        this.meters = meters;
        this.batchSize = castor.normalizer().batchSize();
    }

    /** Normalizes one batch and returns how many records it handled. */
    public int runOnce() {
        NormalizationStore.Result result = store.process(batchSize, this::decide);
        if (result.normalized() > 0) {
            meters.counter("castor.gateway.normalized", "result", "normalized").increment(result.normalized());
        }
        if (result.quarantined() > 0) {
            meters.counter("castor.gateway.normalized", "result", "quarantined").increment(result.quarantined());
        }
        return result.total();
    }

    Decision decide(InboxRecord record) {
        // Only when there is a record: a fail point on an idle gateway would stop it before anything happened
        failPoints.check(FailPoint.BEFORE_NORMALIZE);
        SourceAdapter adapter = adapters().get(record.source());
        if (adapter == null) {
            return new Decision.Quarantined(Rejection.NO_ADAPTER, "No adapter for source " + record.source());
        }
        try {
            List<CanonicalEvent> events = adapter.normalize(record);
            return new Decision.Normalized(stamp(events, record.receptionId()));
        } catch (Rejection rejection) {
            return new Decision.Quarantined(rejection.reasonCode(), truncate(rejection.getMessage()));
        } catch (RuntimeException e) {
            String errorId = UUID.randomUUID().toString();
            LOG.errorf(
                    e, "Adapter %s failed errorId=%s receptionId=%s", adapter.source(), errorId, record.receptionId());
            return new Decision.Quarantined(
                    Rejection.ADAPTER_ERROR, "The adapter failed, see the log for errorId " + errorId);
        }
    }

    private static List<CanonicalEvent> stamp(List<CanonicalEvent> events, UUID receptionId) {
        return IntStream.range(0, events.size())
                .mapToObj(i -> EventIds.stamp(events.get(i), receptionId, i))
                .toList();
    }

    private static String truncate(String detail) {
        String text = detail == null ? "" : detail;
        return text.length() <= MAX_DETAIL ? text : text.substring(0, MAX_DETAIL) + "...";
    }

    private Map<String, SourceAdapter> adapters() {
        Map<String, SourceAdapter> current = adapters;
        if (current == null) {
            Map<String, SourceAdapter> found = new HashMap<>();
            for (SourceAdapter adapter : adapterBeans) {
                SourceAdapter previous = found.put(adapter.source(), adapter);
                if (previous != null) {
                    throw new IllegalStateException("Two adapters for source " + adapter.source());
                }
            }
            adapters = current = Map.copyOf(found);
        }
        return current;
    }
}

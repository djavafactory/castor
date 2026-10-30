package com.djavafactory.castor.core.operations.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import com.djavafactory.castor.contracts.CanonicalEvent;
import com.djavafactory.castor.core.operations.domain.EquipmentRepository;
import com.djavafactory.castor.core.operations.domain.EquipmentState;
import com.djavafactory.castor.core.operations.domain.EquipmentStatusInterval;
import com.djavafactory.castor.core.operations.domain.PortExtents;
import com.djavafactory.castor.core.operations.domain.PublishedAssets;
import com.djavafactory.castor.core.operations.domain.ReconciliationItem;
import com.djavafactory.castor.core.operations.domain.ReconciliationRepository;
import com.djavafactory.castor.core.operations.domain.ReconciliationStatus;
import com.djavafactory.castor.core.operations.domain.StateChange;
import com.djavafactory.castor.core.operations.domain.StateChangeRepository;
import com.djavafactory.castor.core.operations.domain.VesselCall;
import com.djavafactory.castor.core.operations.domain.VesselCallRepository;
import com.djavafactory.castor.core.shared.infrastructure.CastorConfig;
import com.djavafactory.castor.core.support.TestEvents;

/** The handlers wired to in-memory repositories, so a handler test needs no database and no Quarkus. */
final class ProjectionFixture {

    static final long PORT = 1;
    static final Instant T0 = Instant.parse("2026-10-30T08:00:00Z");
    /** A box around Pulau Indah; everything else is outside the port. */
    static final double LON = 101.30, LAT = 2.99;

    final UUID crane = UUID.randomUUID();
    final UUID berth = UUID.randomUUID();
    final UUID pad = UUID.randomUUID();
    final TestEvents.SteppedClock clock = new TestEvents.SteppedClock(T0);
    final TestEvents events = new TestEvents(clock);

    final Equipment equipment = new Equipment();
    final Calls calls = new Calls();
    final Reconciliation reconciliation = new Reconciliation();
    final Changes changes = new Changes();

    final EquipmentStatusHandler status;
    final EquipmentPositionHandler position;
    final EquipmentAssignmentHandler assignment;
    final VesselCallHandler vesselCall;

    ProjectionFixture() {
        PublishedAssets assets = (port, id) -> id.equals(crane)
                ? Optional.of("STS_CRANE")
                : id.equals(berth)
                        ? Optional.of("BERTH")
                        : id.equals(pad) ? Optional.of("STORAGE_PAD") : Optional.empty();
        PortExtents extent = (port, lon, lat) -> lon > 101.0 && lon < 101.6 && lat > 2.8 && lat < 3.2;
        PublishedLayout layout = new PublishedLayout(assets, extent);
        StaleEvents stale = new StaleEvents(reconciliation);
        ObjectMapper json = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        CastorConfig.Sla sla = new CastorConfig.Sla() {
            public Duration equipment() {
                return Duration.ofSeconds(120);
            }

            public Duration vesselCall() {
                return Duration.ofMinutes(15);
            }

            public Duration inventory() {
                return Duration.ofMinutes(15);
            }

            public Duration gateVisit() {
                return Duration.ofMinutes(15);
            }
        };
        StateChanges stateChanges = new StateChanges(new StateChangeRecorder(changes), new StateViews(sla), json);
        status = new EquipmentStatusHandler(equipment, layout, stale, stateChanges);
        position = new EquipmentPositionHandler(equipment, layout, stale, stateChanges);
        assignment = new EquipmentAssignmentHandler(equipment, layout, stale, stateChanges);
        vesselCall = new VesselCallHandler(calls, layout, stale, stateChanges);
    }

    ProjectionContext context(CanonicalEvent event) {
        return new ProjectionContext(PORT, UUID.fromString(event.eventId()), event);
    }

    void handle(EventHandler handler, CanonicalEvent event) {
        handler.handle(context(event));
    }

    static final class Equipment implements EquipmentRepository {
        final Map<UUID, EquipmentState> states = new HashMap<>();
        final List<EquipmentStatusInterval> intervals = new ArrayList<>();

        public Optional<EquipmentState> find(long portId, UUID assetId) {
            return Optional.ofNullable(states.get(assetId));
        }

        public List<EquipmentState> findAll(long portId) {
            return List.copyOf(states.values());
        }

        public EquipmentState insert(EquipmentState s) {
            states.put(s.assetId(), s);
            return s;
        }

        public EquipmentState update(EquipmentState s) {
            EquipmentState next = new EquipmentState(
                    s.portId(),
                    s.assetId(),
                    s.status(),
                    s.statusReason(),
                    s.position(),
                    s.assignment(),
                    s.provenance(),
                    s.lastOccurredAt(),
                    s.lastSourceSequence(),
                    s.version() + 1);
            states.put(s.assetId(), next);
            return next;
        }

        public Optional<EquipmentStatusInterval> openInterval(long portId, UUID assetId) {
            return intervals.stream()
                    .filter(i -> i.assetId().equals(assetId) && i.endedAt() == null)
                    .findFirst();
        }

        public void startInterval(EquipmentStatusInterval interval) {
            intervals.add(interval);
        }

        public boolean closeInterval(long portId, UUID assetId, Instant endedAt) {
            return openInterval(portId, assetId)
                    .map(open -> replace(open, endedAt))
                    .orElse(false);
        }

        public Optional<EquipmentStatusInterval> intervalAt(long portId, UUID assetId, Instant at) {
            return intervals.stream()
                    .filter(i -> i.assetId().equals(assetId)
                            && !i.startedAt().isAfter(at)
                            && (i.endedAt() == null || i.endedAt().isAfter(at)))
                    .findFirst();
        }

        public boolean endInterval(long portId, UUID assetId, Instant startedAt, Instant endedAt) {
            return intervals.stream()
                    .filter(i -> i.assetId().equals(assetId) && i.startedAt().equals(startedAt))
                    .findFirst()
                    .map(i -> replace(i, endedAt))
                    .orElse(false);
        }

        public List<EquipmentStatusInterval> intervals(long portId, UUID assetId, int limit) {
            return intervals.stream()
                    .filter(i -> i.assetId().equals(assetId))
                    .sorted((a, b) -> b.startedAt().compareTo(a.startedAt()))
                    .limit(limit)
                    .toList();
        }

        /** The intervals of the machine, oldest first, as {@code STATUS start..end} text. */
        List<String> history(UUID assetId) {
            return intervals.stream()
                    .filter(i -> i.assetId().equals(assetId))
                    .sorted((a, b) -> a.startedAt().compareTo(b.startedAt()))
                    .map(i -> i.status() + " " + (i.startedAt().getEpochSecond() - T0.getEpochSecond()) + ".."
                            + (i.endedAt() == null
                                    ? "open"
                                    : String.valueOf(i.endedAt().getEpochSecond() - T0.getEpochSecond())))
                    .toList();
        }

        private boolean replace(EquipmentStatusInterval old, Instant endedAt) {
            intervals.set(
                    intervals.indexOf(old),
                    new EquipmentStatusInterval(
                            old.portId(),
                            old.assetId(),
                            old.status(),
                            old.reason(),
                            old.startedAt(),
                            endedAt,
                            old.source(),
                            old.quality()));
            return true;
        }
    }

    static final class Calls implements VesselCallRepository {
        final Map<String, VesselCall> byCode = new LinkedHashMap<>();

        public Optional<VesselCall> find(long portId, String callCode) {
            return Optional.ofNullable(byCode.get(callCode));
        }

        public List<VesselCall> findAll(long portId) {
            return List.copyOf(byCode.values());
        }

        public VesselCall insert(VesselCall c) {
            VesselCall stored = new VesselCall(
                    UUID.randomUUID(),
                    c.portId(),
                    c.callCode(),
                    c.vesselName(),
                    c.imo(),
                    c.loaM(),
                    c.beamM(),
                    c.status(),
                    c.eta(),
                    c.ata(),
                    c.etd(),
                    c.atd(),
                    c.berthAssetId(),
                    c.provenance(),
                    c.lastOccurredAt(),
                    c.lastSourceSequence(),
                    0);
            byCode.put(c.callCode(), stored);
            return stored;
        }

        public VesselCall update(VesselCall c) {
            VesselCall stored = new VesselCall(
                    c.id(),
                    c.portId(),
                    c.callCode(),
                    c.vesselName(),
                    c.imo(),
                    c.loaM(),
                    c.beamM(),
                    c.status(),
                    c.eta(),
                    c.ata(),
                    c.etd(),
                    c.atd(),
                    c.berthAssetId(),
                    c.provenance(),
                    c.lastOccurredAt(),
                    c.lastSourceSequence(),
                    c.version() + 1);
            byCode.put(c.callCode(), stored);
            return stored;
        }
    }

    static final class Reconciliation implements ReconciliationRepository {
        final List<ReconciliationItem> items = new ArrayList<>();

        public ReconciliationItem open(ReconciliationItem i) {
            items.add(i);
            return i;
        }

        public Optional<ReconciliationItem> find(long id) {
            return Optional.empty();
        }

        public List<ReconciliationItem> findByStatus(long portId, ReconciliationStatus status, int limit) {
            return items;
        }

        public boolean resolve(long id, String resolvedBy, String note, Instant at) {
            return false;
        }
    }

    static final class Changes implements StateChangeRepository {
        final List<StateChange> all = new ArrayList<>();

        public StateChange append(
                long portId,
                String entityType,
                String entityId,
                long entityVersion,
                Instant occurredAt,
                Map<String, Object> body) {
            StateChange change = new StateChange(
                    portId, all.size() + 1L, entityType, entityId, entityVersion, occurredAt, body, null);
            all.add(change);
            return change;
        }

        public List<StateChange> after(long portId, long sequence, int limit) {
            return all.stream()
                    .filter(c -> c.sequence() > sequence)
                    .limit(limit)
                    .toList();
        }

        public List<StateChange> unpublished(long portId, int limit) {
            return all;
        }

        public void markPublished(long portId, long upToSequence, Instant at) {}

        public Map<Long, String> portsWithUnpublished() {
            return Map.of();
        }

        public boolean tryLockPublishing(long portId) {
            return true;
        }

        public long lastSequence(long portId) {
            return all.size();
        }
    }
}

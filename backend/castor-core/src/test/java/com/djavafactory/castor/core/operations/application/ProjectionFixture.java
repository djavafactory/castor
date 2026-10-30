package com.djavafactory.castor.core.operations.application;

import java.math.BigDecimal;
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
import com.djavafactory.castor.contracts.Quantity;
import com.djavafactory.castor.contracts.Unit;
import com.djavafactory.castor.core.operations.domain.CargoFamily;
import com.djavafactory.castor.core.operations.domain.CargoFamilyRepository;
import com.djavafactory.castor.core.operations.domain.CargoLot;
import com.djavafactory.castor.core.operations.domain.CargoMovement;
import com.djavafactory.castor.core.operations.domain.CargoRepository;
import com.djavafactory.castor.core.operations.domain.EquipmentRepository;
import com.djavafactory.castor.core.operations.domain.EquipmentState;
import com.djavafactory.castor.core.operations.domain.EquipmentStatusInterval;
import com.djavafactory.castor.core.operations.domain.GateVisit;
import com.djavafactory.castor.core.operations.domain.GateVisitRepository;
import com.djavafactory.castor.core.operations.domain.InventoryAdjustment;
import com.djavafactory.castor.core.operations.domain.InventoryRepository;
import com.djavafactory.castor.core.operations.domain.PortExtents;
import com.djavafactory.castor.core.operations.domain.PublishedAssets;
import com.djavafactory.castor.core.operations.domain.ReconciliationItem;
import com.djavafactory.castor.core.operations.domain.ReconciliationKind;
import com.djavafactory.castor.core.operations.domain.ReconciliationRepository;
import com.djavafactory.castor.core.operations.domain.ReconciliationStatus;
import com.djavafactory.castor.core.operations.domain.SourcePrecedence;
import com.djavafactory.castor.core.operations.domain.StateChange;
import com.djavafactory.castor.core.operations.domain.StateChangeRepository;
import com.djavafactory.castor.core.operations.domain.StorageInventory;
import com.djavafactory.castor.core.operations.domain.VesselCall;
import com.djavafactory.castor.core.operations.domain.VesselCallRepository;
import com.djavafactory.castor.core.operations.domain.VesselCallValueRepository;
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
    /** A storage area with no capacity in the layout (UNKNOWN), a buffer with one of 100 tonnes, a gate and a tank. */
    final UUID yard = UUID.randomUUID();

    final UUID buffer = UUID.randomUUID();
    final UUID gate = UUID.randomUUID();
    final UUID tank = UUID.randomUUID();
    final TestEvents.SteppedClock clock = new TestEvents.SteppedClock(T0);
    final TestEvents events = new TestEvents(clock);

    final Equipment equipment = new Equipment();
    final Calls calls = new Calls();
    final Reconciliation reconciliation = new Reconciliation();
    final Changes changes = new Changes();
    final Values values = new Values();
    final Cargo cargo = new Cargo();
    final Inventories inventories = new Inventories();
    final Gates gates = new Gates();
    final Map<String, List<String>> precedence = new HashMap<>(SourcePrecedence.VESSEL_CALL_DEFAULTS);

    final EquipmentStatusHandler status;
    final EquipmentPositionHandler position;
    final EquipmentAssignmentHandler assignment;
    final VesselCallHandler vesselCall;
    final CargoLotRegisteredHandler lotRegistered;
    final CargoLotMovedHandler lotMoved;
    final StorageLevelHandler level;
    final InventoryAdjustedHandler adjusted;
    final GateTransactionHandler gateTransaction;

    ProjectionFixture() {
        PublishedAssets assets = new PublishedAssets() {
            public Optional<String> typeCode(long port, UUID id) {
                if (id.equals(crane)) {
                    return Optional.of("STS_CRANE");
                }
                if (id.equals(berth)) {
                    return Optional.of("BERTH");
                }
                if (id.equals(pad)) {
                    return Optional.of("STORAGE_PAD");
                }
                if (id.equals(gate)) {
                    return Optional.of("GATE");
                }
                return isStorage(port, id) ? Optional.of("STORAGE") : Optional.empty();
            }

            public boolean isStorage(long port, UUID id) {
                return id.equals(pad) || id.equals(yard) || id.equals(buffer) || id.equals(tank);
            }

            public Optional<Quantity> usableCapacity(long port, UUID id) {
                return id.equals(buffer)
                        ? Optional.of(new Quantity(new BigDecimal("100"), Unit.TONNES))
                        : Optional.empty();
            }
        };
        PortExtents extent = (port, lon, lat) -> lon > 101.0 && lon < 101.6 && lat > 2.8 && lat < 3.2;
        PublishedLayout layout = new PublishedLayout(assets, extent);
        Reconciliations reconciliations = new Reconciliations(reconciliation, clock);
        StaleEvents stale = new StaleEvents(reconciliations);
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
        StateChanges stateChanges =
                new StateChanges(new StateChangeRecorder(changes), new StateViews(sla, assets), json);
        status = new EquipmentStatusHandler(equipment, layout, stale, stateChanges);
        position = new EquipmentPositionHandler(equipment, layout, stale, stateChanges);
        assignment = new EquipmentAssignmentHandler(equipment, layout, stale, stateChanges);
        SourcePrecedence order = (port, type, field) -> Optional.ofNullable(precedence.get(field));
        vesselCall = new VesselCallHandler(calls, values, order, layout, stale, reconciliations, stateChanges);
        InventoryBalances balances = new InventoryBalances(inventories, layout, reconciliations, stateChanges);
        lotRegistered = new CargoLotRegisteredHandler(cargo, families(), stateChanges);
        lotMoved = new CargoLotMovedHandler(cargo, layout, balances, stale, stateChanges);
        level = new StorageLevelHandler(inventories, layout, balances, reconciliations, stale);
        adjusted = new InventoryAdjustedHandler(inventories, layout, balances);
        gateTransaction = new GateTransactionHandler(gates, layout, stale, stateChanges);
    }

    private static CargoFamilyRepository families() {
        return new CargoFamilyRepository() {
            private final List<CargoFamily> all = List.of(
                    new CargoFamily("DRY_BULK", "Dry bulk", Unit.TONNES),
                    new CargoFamily("LIQUID_BULK", "Liquid bulk", Unit.CUBIC_METRES),
                    new CargoFamily("STEEL", "Steel", Unit.TONNES));

            public List<CargoFamily> findAll() {
                return all;
            }

            public Optional<CargoFamily> find(String code) {
                return all.stream().filter(f -> f.code().equals(code)).findFirst();
            }
        };
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

        public List<ReconciliationItem> findByStatus(
                long portId, ReconciliationStatus status, ReconciliationKind kind, int limit) {
            return items;
        }

        public boolean hasOpen(long portId, ReconciliationKind kind, String entityType, String entityId, String field) {
            return items.stream()
                    .anyMatch(i -> i.kind() == kind
                            && i.entityId().equals(entityId)
                            && field.equals(i.detail().get("field")));
        }

        public boolean resolve(long id, String resolvedBy, String note, Instant at) {
            return false;
        }

        List<ReconciliationItem> of(ReconciliationKind kind) {
            return items.stream().filter(i -> i.kind() == kind).toList();
        }
    }

    static final class Values implements VesselCallValueRepository {
        final List<Value> all = new ArrayList<>();
        private final Map<Value, UUID> calls = new HashMap<>();

        public void append(long portId, UUID callId, UUID eventId, Value value) {
            all.add(value);
            calls.put(value, callId);
        }

        public List<Value> latestBySource(UUID callId) {
            Map<String, Value> latest = new LinkedHashMap<>();
            for (Value v : all) {
                if (calls.get(v).equals(callId)) {
                    latest.put(v.field() + "/" + v.source(), v);
                }
            }
            return List.copyOf(latest.values());
        }

        public Optional<Value> lastFrom(UUID callId, String source) {
            return all.stream()
                    .filter(v -> calls.get(v).equals(callId) && v.source().equals(source))
                    .reduce((a, b) -> b);
        }

        public List<Value> history(UUID callId, String field) {
            return all.stream()
                    .filter(v -> calls.get(v).equals(callId) && v.field().equals(field))
                    .toList();
        }
    }

    static final class Cargo implements CargoRepository {
        final Map<String, CargoLot> lots = new LinkedHashMap<>();
        final List<CargoMovement> movements = new ArrayList<>();

        public Optional<CargoLot> findLot(long portId, String lotCode) {
            return Optional.ofNullable(lots.get(lotCode));
        }

        public Optional<CargoLot> findLot(UUID id) {
            return lots.values().stream().filter(l -> l.id().equals(id)).findFirst();
        }

        public CargoLot insertLot(CargoLot l) {
            CargoLot stored = with(l, UUID.randomUUID(), 0);
            lots.put(l.lotCode(), stored);
            return stored;
        }

        public CargoLot updateLot(CargoLot l) {
            CargoLot stored = with(l, l.id(), l.version() + 1);
            lots.put(l.lotCode(), stored);
            return stored;
        }

        public boolean appendMovement(CargoMovement m) {
            if (movements.stream().anyMatch(o -> o.eventId().equals(m.eventId()))) {
                return false;
            }
            movements.add(m);
            return true;
        }

        public List<CargoMovement> movements(UUID lotId) {
            return movements.stream().filter(m -> m.lotId().equals(lotId)).toList();
        }

        private static CargoLot with(CargoLot l, UUID id, long version) {
            return new CargoLot(
                    id,
                    l.portId(),
                    l.lotCode(),
                    l.consignmentCode(),
                    l.cargoFamily(),
                    l.commodity(),
                    l.unit(),
                    l.registeredQuantity(),
                    l.massBasis(),
                    l.currentLocationId(),
                    l.locationOccurredAt(),
                    l.source(),
                    l.quality(),
                    version);
        }
    }

    static final class Inventories implements InventoryRepository {
        final Map<String, StorageInventory> rows = new LinkedHashMap<>();
        final List<InventoryAdjustment> adjustments = new ArrayList<>();

        private static String key(UUID storage, String commodity) {
            return storage + ":" + commodity;
        }

        public Optional<StorageInventory> find(long portId, UUID storageAssetId, String commodity) {
            return Optional.ofNullable(rows.get(key(storageAssetId, commodity)));
        }

        public Optional<StorageInventory> findForUpdate(long portId, UUID storageAssetId, String commodity) {
            return find(portId, storageAssetId, commodity);
        }

        public List<StorageInventory> findByStorage(long portId, UUID storageAssetId) {
            return rows.values().stream()
                    .filter(r -> r.storageAssetId().equals(storageAssetId))
                    .toList();
        }

        public StorageInventory insert(StorageInventory i) {
            rows.put(key(i.storageAssetId(), i.commodity()), i);
            return i;
        }

        public StorageInventory update(StorageInventory i) {
            StorageInventory next = new StorageInventory(
                    i.portId(),
                    i.storageAssetId(),
                    i.commodity(),
                    i.quantity(),
                    i.quantityObservedAt(),
                    i.source(),
                    i.quality(),
                    i.version() + 1,
                    i.massBasis(),
                    i.needsReconciliation(),
                    i.level());
            rows.put(key(i.storageAssetId(), i.commodity()), next);
            return next;
        }

        public boolean appendAdjustment(InventoryAdjustment a) {
            adjustments.add(a);
            return true;
        }

        public List<InventoryAdjustment> adjustments(long portId, UUID storageAssetId) {
            return adjustments;
        }

        /** The book balance of the area, or null while unknown. */
        BigDecimal book(UUID storage, String commodity) {
            StorageInventory row = rows.get(key(storage, commodity));
            return row == null || row.quantity() == null ? null : row.quantity().value();
        }
    }

    static final class Gates implements GateVisitRepository {
        final Map<String, GateVisit> visits = new LinkedHashMap<>();

        public Optional<GateVisit> find(long portId, String truckVisitId, UUID gateAssetId) {
            return Optional.ofNullable(visits.get(truckVisitId + ":" + gateAssetId));
        }

        public GateVisit save(GateVisit v) {
            GateVisit previous = visits.get(v.truckVisitId() + ":" + v.gateAssetId());
            GateVisit stored = new GateVisit(
                    v.portId(),
                    v.truckVisitId(),
                    v.gateAssetId(),
                    v.inAt(),
                    v.outAt(),
                    v.lane(),
                    v.source(),
                    v.quality(),
                    previous == null ? 0 : previous.version() + 1);
            visits.put(v.truckVisitId() + ":" + v.gateAssetId(), stored);
            return stored;
        }

        long open() {
            return visits.values().stream()
                    .filter(v -> v.inAt() != null && v.outAt() == null)
                    .count();
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

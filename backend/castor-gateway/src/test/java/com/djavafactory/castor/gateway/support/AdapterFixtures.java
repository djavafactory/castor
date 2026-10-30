package com.djavafactory.castor.gateway.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import com.djavafactory.castor.contracts.CanonicalEvent;
import com.djavafactory.castor.contracts.CanonicalEventCodec;
import com.djavafactory.castor.contracts.CanonicalEventValidator;
import com.djavafactory.castor.contracts.Quality;
import com.djavafactory.castor.contracts.Unit;
import com.djavafactory.castor.gateway.adapters.application.AdapterSupport;
import com.djavafactory.castor.gateway.intake.domain.RecordKeys;
import com.djavafactory.castor.gateway.intake.domain.Source;
import com.djavafactory.castor.gateway.mapping.domain.MappingRow;
import com.djavafactory.castor.gateway.mapping.domain.MappingStore;
import com.djavafactory.castor.gateway.mapping.domain.Mappings;
import com.djavafactory.castor.gateway.normalize.domain.EventIds;
import com.djavafactory.castor.gateway.normalize.domain.InboxRecord;
import com.djavafactory.castor.gateway.shared.domain.Json;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the adapter unit tests need and no database: an in-memory {@link MappingStore} with the demo mappings, the support
 * object every adapter takes, and the golden files (an input record of a source and the canonical JSON it must become).
 */
public final class AdapterFixtures {

    public static final String PORT = "westports";
    public static final Instant RECEIVED_AT = Instant.parse("2026-10-29T08:30:00Z");
    public static final UUID RECEPTION_ID = UUID.fromString("0198a6f0-0000-7000-8000-000000000001");

    private static final Instant FOREVER_AGO = Instant.EPOCH;
    private static final CanonicalEventCodec CODEC = new CanonicalEventCodec();

    private AdapterFixtures() {}

    /** Mapping rows in memory. */
    public static final class InMemoryMappings implements MappingStore {

        private final Map<String, List<MappingRow>> identity = new HashMap<>();
        private final Map<String, List<MappingRow>> cargo = new HashMap<>();
        private final Map<String, Set<Unit>> units = new HashMap<>();

        public InMemoryMappings asset(String system, String type, String sourceId, String canonicalId) {
            return asset(system, type, sourceId, canonicalId, FOREVER_AGO, null);
        }

        public InMemoryMappings asset(
                String system, String type, String sourceId, String canonicalId, Instant from, Instant to) {
            identity.computeIfAbsent(key(PORT, system, type, sourceId), k -> new ArrayList<>())
                    .add(new MappingRow(canonicalId, from, to));
            return this;
        }

        public InMemoryMappings code(String system, String type, String sourceCode, String canonicalCode) {
            cargo.computeIfAbsent(key("", system, type, sourceCode), k -> new ArrayList<>())
                    .add(new MappingRow(canonicalCode, FOREVER_AGO, null));
            return this;
        }

        public InMemoryMappings allow(String family, Unit... allowed) {
            units.put(family, EnumSet.copyOf(List.of(allowed)));
            return this;
        }

        @Override
        public List<MappingRow> identity(String portId, String system, String type, String sourceId) {
            return identity.getOrDefault(key(portId, system, type, sourceId), List.of());
        }

        @Override
        public List<MappingRow> cargo(String system, String type, String sourceId) {
            return cargo.getOrDefault(key("", system, type, sourceId), List.of());
        }

        @Override
        public Set<Unit> unitsOf(String familyCode) {
            return units.getOrDefault(familyCode, Set.of());
        }

        @Override
        public Map<String, String> publishedAssets(String portId) {
            return Map.of();
        }

        @Override
        public boolean insertIfMissing(
                String portId,
                String system,
                String type,
                String sourceId,
                String canonicalId,
                Instant from,
                String createdBy,
                Instant createdAt) {
            throw new UnsupportedOperationException();
        }

        private static String key(String... parts) {
            return String.join("|", parts);
        }
    }

    /** The demo mappings the golden files use: every canonical id is "asset-" and the lower-case code. */
    public static InMemoryMappings demoMappings() {
        InMemoryMappings m = new InMemoryMappings();
        m.asset("cbas", "BERTH", "B3", "asset-berth-b3");
        m.asset("itap", "STORAGE", "PAD-A1", "asset-pad-a1");
        m.asset("itap", "STORAGE", "SL1", "asset-sl1");
        m.asset("itap", "EQUIPMENT", "MHC1", "asset-mhc1");
        m.asset("scss", "GATE", "G1", "asset-g1");
        m.asset("edic", "VESSEL_CALL", "EDI-AURORA-0412", "MV-AURORA-0412");
        m.asset("sensors", "EQUIPMENT", "GPS-MHC1", "asset-mhc1");
        m.asset("sensors", "EQUIPMENT", "CV1-RATE", "asset-cv1");
        m.asset("sensors", "STORAGE", "TK1-LEVEL", "asset-tk1");
        m.asset("sensors", "STORAGE", "SL1-LEVEL", "asset-sl1");
        m.asset("sensors", "GATE", "CAM-G1", "asset-g1");
        for (String family : List.of("BREAKBULK", "DRY_BULK", "LIQUID_BULK", "RORO", "CONTAINER")) {
            m.code("itap", "CARGO_FAMILY", family, family);
        }
        for (String commodity : List.of("COAL", "GRAIN", "EDOIL", "OIL", "STEEL")) {
            m.code("itap", "COMMODITY", commodity, commodity);
            m.code("sensors", "COMMODITY", commodity, commodity);
        }
        m.allow("DRY_BULK", Unit.TONNES);
        m.allow("BREAKBULK", Unit.TONNES, Unit.PIECES);
        m.allow("LIQUID_BULK", Unit.CUBIC_METRES, Unit.TONNES);
        return m;
    }

    public static AdapterSupport support(MappingStore store) {
        return support(store, Quality.SIMULATED);
    }

    public static AdapterSupport support(MappingStore store, Quality quality) {
        return new AdapterSupport(
                new Mappings(store, InstantSource.fixed(RECEIVED_AT)),
                (port, source) -> quality,
                new CanonicalEventValidator(),
                CODEC);
    }

    /** A record of the inbox, its sourceEventId made as the intake makes it. */
    public static InboxRecord record(Source source, String rawJson) {
        try {
            JsonNode raw = Json.parse(rawJson);
            return new InboxRecord(
                    RECEPTION_ID, PORT, source.code(), RecordKeys.sourceEventId(source, raw), RECEIVED_AT, raw);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static String resource(String path) {
        try (InputStream in = AdapterFixtures.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The input of golden file {@code name}, as the inbox record of the source. */
    public static InboxRecord goldenInput(Source source, String name) {
        return record(source, resource("/golden/" + name + ".input.json"));
    }

    /**
     * Compares the event with golden file {@code name} field by field, the payload included. Only the {@code eventId} is
     * not in the file: it is the name-based id of the reception id and index 0, which the normalizer sets anyway.
     */
    public static void assertGolden(String name, CanonicalEvent actual) {
        try {
            JsonNode expected = Json.parse(resource("/golden/" + name + ".expected.json"));
            JsonNode event = Json.parse(CODEC.encode(actual));
            assertEquals(
                    EventIds.of(RECEPTION_ID, 0).toString(),
                    event.get("eventId").asText(),
                    "eventId");
            ((com.fasterxml.jackson.databind.node.ObjectNode) event).remove("eventId");
            Iterator<String> names = expected.fieldNames();
            while (names.hasNext()) {
                String field = names.next();
                assertEquals(expected.get(field), event.get(field), name + ": field " + field);
            }
            Set<String> extra = new java.util.TreeSet<>();
            event.fieldNames().forEachRemaining(extra::add);
            expected.fieldNames().forEachRemaining(extra::remove);
            assertEquals(Set.of(), extra, name + ": fields the golden file does not have");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

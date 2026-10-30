package com.djavafactory.castor.gateway.mapping.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Set;

import com.djavafactory.castor.contracts.Unit;
import com.djavafactory.castor.gateway.shared.domain.TtlCache;

/**
 * Resolves what a source calls an asset, a call or a cargo code to the canonical id valid at the time of the event. The
 * rows of a source id are read once a minute (on the injected clock); a row added or ended in between is seen after that
 * minute, or at once after {@link #invalidate()}. No row is {@code UNKNOWN_MAPPING}, more than one is
 * {@code AMBIGUOUS_MAPPING}: nothing is ever picked.
 */
public final class Mappings {

    /** How long the rows of a source id are kept. */
    public static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private static final int MAX_CACHED = 50_000;

    private record Key(String kind, String port, String system, String entityType, String sourceId) {}

    private final MappingStore store;
    private final TtlCache<Key, List<MappingRow>> rows;
    private final TtlCache<String, Set<Unit>> units;

    public Mappings(MappingStore store, InstantSource clock) {
        this.store = store;
        this.rows = new TtlCache<>(clock, CACHE_TTL, MAX_CACHED);
        this.units = new TtlCache<>(clock, CACHE_TTL, MAX_CACHED);
    }

    /** The canonical id of a physical asset or a call ref, valid at {@code at}. */
    public String identity(String portId, String sourceSystem, String entityType, String sourceId, Instant at)
            throws MappingException {
        List<MappingRow> found = rows.get(
                new Key("identity", portId, sourceSystem, entityType, sourceId),
                key -> store.identity(portId, sourceSystem, entityType, sourceId));
        return pick(
                found,
                at,
                MappingException.UNKNOWN_MAPPING,
                MappingException.AMBIGUOUS_MAPPING,
                () -> sourceSystem + " " + entityType + " '" + sourceId + "'",
                entityType,
                sourceId);
    }

    /** The canonical code of a source family or commodity code, valid at {@code at}. */
    public String cargoCode(String sourceSystem, String entityType, String sourceId, Instant at)
            throws MappingException {
        List<MappingRow> found = rows.get(
                new Key("cargo", "", sourceSystem, entityType, sourceId),
                key -> store.cargo(sourceSystem, entityType, sourceId));
        return pick(
                found,
                at,
                MappingException.UNKNOWN_CARGO_MAPPING,
                MappingException.AMBIGUOUS_MAPPING,
                () -> sourceSystem + " " + entityType + " code '" + sourceId + "'",
                null,
                null);
    }

    /** The unit must be one the canonical cargo family is measured in; a family without a rule is not checked. */
    public void requireFit(String canonicalFamily, Unit unit) throws MappingException {
        Set<Unit> allowed = units.get(canonicalFamily, store::unitsOf);
        if (!allowed.isEmpty() && !allowed.contains(unit)) {
            throw new MappingException(
                    MappingException.MALFORMED_UNIT,
                    "Unit " + unit + " does not fit cargo family " + canonicalFamily + " (" + allowed + ")");
        }
    }

    /** Forgets every cached row: the next lookup reads the database. */
    public void invalidate() {
        rows.clear();
        units.clear();
    }

    private static String pick(
            List<MappingRow> found,
            Instant at,
            String noneCode,
            String manyCode,
            java.util.function.Supplier<String> what,
            String subjectType,
            String subjectId)
            throws MappingException {
        List<MappingRow> valid = found.stream().filter(row -> row.validAt(at)).toList();
        if (valid.isEmpty()) {
            throw new MappingException(noneCode, "No mapping for " + what.get() + " at " + at, subjectType, subjectId);
        }
        if (valid.size() > 1) {
            throw new MappingException(
                    manyCode, "More than one mapping for " + what.get() + " at " + at, subjectType, subjectId);
        }
        return valid.get(0).canonicalId();
    }
}

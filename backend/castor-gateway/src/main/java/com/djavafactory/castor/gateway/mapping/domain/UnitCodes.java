package com.djavafactory.castor.gateway.mapping.domain;

import java.util.Map;

import com.djavafactory.castor.contracts.Unit;

/**
 * The unit codes the sources write, and the canonical unit of each. Exact codes only (rule R4): there is no case folding,
 * no plural and no conversion, and a rate is not an amount. The canonical names are accepted too, for sources that
 * already speak canonical.
 */
public final class UnitCodes {

    private static final Map<String, Unit> AMOUNTS = Map.ofEntries(
            Map.entry("t", Unit.TONNES),
            Map.entry("MT", Unit.TONNES),
            Map.entry("pcs", Unit.PIECES),
            Map.entry("m3", Unit.CUBIC_METRES),
            Map.entry("units", Unit.VEHICLES),
            Map.entry("TONNES", Unit.TONNES),
            Map.entry("PIECES", Unit.PIECES),
            Map.entry("CUBIC_METRES", Unit.CUBIC_METRES),
            Map.entry("VEHICLES", Unit.VEHICLES),
            Map.entry("TRUCKS", Unit.TRUCKS),
            Map.entry("LIFTS", Unit.LIFTS));

    private static final Map<String, Unit> RATES = Map.ofEntries(
            Map.entry("t/h", Unit.TONNES_PER_HOUR),
            Map.entry("MT/h", Unit.TONNES_PER_HOUR),
            Map.entry("pcs/h", Unit.PIECES_PER_HOUR),
            Map.entry("m3/h", Unit.CUBIC_METRES_PER_HOUR),
            Map.entry("units/h", Unit.VEHICLES_PER_HOUR),
            Map.entry("TONNES_PER_HOUR", Unit.TONNES_PER_HOUR),
            Map.entry("PIECES_PER_HOUR", Unit.PIECES_PER_HOUR),
            Map.entry("CUBIC_METRES_PER_HOUR", Unit.CUBIC_METRES_PER_HOUR),
            Map.entry("VEHICLES_PER_HOUR", Unit.VEHICLES_PER_HOUR),
            Map.entry("TRUCKS_PER_HOUR", Unit.TRUCKS_PER_HOUR),
            Map.entry("LIFTS_PER_HOUR", Unit.LIFTS_PER_HOUR));

    private UnitCodes() {}

    /** The unit of a quantity code such as {@code t} or {@code pcs}. */
    public static Unit amount(String code) throws MappingException {
        return lookup(AMOUNTS, RATES, code, "a rate, not an amount");
    }

    /** The unit of a rate code such as {@code t/h}. */
    public static Unit rate(String code) throws MappingException {
        return lookup(RATES, AMOUNTS, code, "an amount, not a rate");
    }

    private static Unit lookup(Map<String, Unit> table, Map<String, Unit> other, String code, String otherKind)
            throws MappingException {
        if (code == null || code.isBlank()) {
            throw new MappingException(MappingException.MALFORMED_UNIT, "The unit code is missing");
        }
        Unit unit = table.get(code);
        if (unit != null) {
            return unit;
        }
        String why = other.containsKey(code) ? "is " + otherKind : "is not a known unit code";
        throw new MappingException(MappingException.MALFORMED_UNIT, "Unit code '" + code + "' " + why);
    }
}

package com.djavafactory.castor.gateway.mapping.domain;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import com.djavafactory.castor.contracts.Unit;

/**
 * Reads a quantity and its unit from a message and refuses anything unclear: no unit, two units, an unknown code, a
 * negative number. The value is never changed, only checked. A quantity is a JSON number with the unit in a separate
 * field, or text such as {@code "12.5 t"} with the unit inside.
 */
public final class Amounts {

    /** A number and the unit it is in. */
    public record Amount(BigDecimal value, Unit unit) {}

    private static final Pattern TEXT = Pattern.compile("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*(\\S+)?\\s*$");

    private Amounts() {}

    /**
     * @param quantity a number or text with a unit inside
     * @param unitField the separate unit field, null or missing when the unit is inside the quantity
     */
    public static Amount amount(JsonNode quantity, JsonNode unitField) throws MappingException {
        String separate = text(unitField);
        BigDecimal value;
        String embedded = null;
        if (quantity != null && quantity.isNumber()) {
            value = quantity.decimalValue();
        } else if (quantity != null && quantity.isTextual()) {
            Matcher m = TEXT.matcher(quantity.asText());
            if (!m.matches()) {
                throw new MappingException(
                        MappingException.MALFORMED_UNIT, "The quantity '" + quantity.asText() + "' is not a number");
            }
            value = new BigDecimal(m.group(1));
            embedded = m.group(2);
        } else {
            throw new MappingException(MappingException.MALFORMED_UNIT, "The quantity is missing or not a number");
        }
        if (embedded != null && separate != null) {
            throw new MappingException(
                    MappingException.MALFORMED_UNIT,
                    "Two units in one message: '" + embedded + "' in the quantity and '" + separate
                            + "' in the unit field");
        }
        Unit unit = UnitCodes.amount(embedded != null ? embedded : separate);
        nonNegative(value);
        return new Amount(value, unit);
    }

    /** A rate in a number and a rate code such as {@code t/h}. */
    public static Amount rate(JsonNode rate, JsonNode unitField) throws MappingException {
        if (rate == null || !rate.isNumber()) {
            throw new MappingException(MappingException.MALFORMED_UNIT, "The rate is missing or not a number");
        }
        Unit unit = UnitCodes.rate(text(unitField));
        nonNegative(rate.decimalValue());
        return new Amount(rate.decimalValue(), unit);
    }

    private static void nonNegative(BigDecimal value) throws MappingException {
        if (value.signum() < 0) {
            throw new MappingException(MappingException.MALFORMED_UNIT, "The quantity " + value + " is negative");
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() || node.asText().isBlank() ? null : node.asText();
    }
}

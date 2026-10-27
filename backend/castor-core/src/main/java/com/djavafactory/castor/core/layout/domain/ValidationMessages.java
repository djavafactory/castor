package com.djavafactory.castor.core.layout.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rule catalogue of README section 6: code, level and message wording. The same list is kept in
 * fixtures/validation-rules.json and in the frontend (lib/validation/rules.ts); tests on both sides compare their copy
 * with the file, so the browser and the server can never word a rule differently.
 *
 * <p>A template holds {@code {name}} style slots. A code with more than one wording has named variants.
 */
public final class ValidationMessages {

    /** The variant used when a code has only one wording. */
    public static final String DEFAULT = "default";

    /** One wording of one rule. */
    public record Rule(String code, String variant, IssueLevel level, String template) {}

    private static final List<Rule> RULES = List.of(
            rule("GEOM_INVALID", DEFAULT, IssueLevel.ERROR, "{name} has an invalid shape"),
            // named: the Issues list, where the row has room for the name; edges: the drawing tool, which needs the
            // edges
            rule(
                    "SELF_INTERSECTION",
                    "named",
                    IssueLevel.ERROR,
                    "{name} has a self-intersecting outline and cannot be published"),
            rule("SELF_INTERSECTION", "edges", IssueLevel.ERROR, "The outline crosses itself (edges {i} and {j})"),
            rule("TOO_FEW_POINTS", "polygon", IssueLevel.ERROR, "{name} needs at least three corners"),
            rule("TOO_FEW_POINTS", "line", IssueLevel.ERROR, "{name} needs at least two points"),
            rule("TOO_SMALL", DEFAULT, IssueLevel.ERROR, "The outline is smaller than 4 m2"),
            rule("WRONG_GEOMETRY_KIND", DEFAULT, IssueLevel.ERROR, "{name}: geometry does not match type {type}"),
            rule("OUT_OF_PORT", DEFAULT, IssueLevel.ERROR, "{name} lies outside the port extent"),
            rule("ON_WATER", DEFAULT, IssueLevel.ERROR, "Extends past the quay edge into the water"),
            rule("OUTSIDE_LAND", DEFAULT, IssueLevel.ERROR, "Outside the land area"),
            rule(
                    "IN_EXCLUSION_ZONE",
                    DEFAULT,
                    IssueLevel.ERROR,
                    "Inside safety exclusion zone {zone}. Move it, or record a documented override in the inspector"),
            rule("OVERLAP", DEFAULT, IssueLevel.ERROR, "Overlaps {other}"),
            rule("BERTH_OVERLAP", DEFAULT, IssueLevel.ERROR, "Overlaps {other} along the quay"),
            rule("CRANE_OFF_BERTH", DEFAULT, IssueLevel.ERROR, "A rail-mounted quay crane must stand on a berth"),
            rule("DIM_OUT_OF_RANGE", "min", IssueLevel.ERROR, "{label}: enter a number of at least {limit}"),
            rule("DIM_OUT_OF_RANGE", "max", IssueLevel.ERROR, "{label}: enter a number of at most {limit}"),
            rule("UNKNOWN_TYPE", DEFAULT, IssueLevel.ERROR, "{name}: unknown asset type {type}"),
            rule(
                    "TRACE_SOURCE_NOT_ALLOWED",
                    DEFAULT,
                    IssueLevel.ERROR,
                    "{name} was traced from unlicensed imagery. Publishing is blocked until its source is replaced with licensed or surveyed data"),
            rule("ON_ROAD", DEFAULT, IssueLevel.WARNING, "{block} sits on road {road}"),
            rule(
                    "PILE_SLOT_TOO_SMALL",
                    DEFAULT,
                    IssueLevel.WARNING,
                    "{block}: a {bp} x {rp} m slot is too small to hold a pile"),
            rule("APPROXIMATE_SOURCE", "one", IssueLevel.WARNING, "1 shape has no survey"),
            rule("APPROXIMATE_SOURCE", "many", IssueLevel.WARNING, "{n} shapes have no survey"));

    private ValidationMessages() {}

    private static Rule rule(String code, String variant, IssueLevel level, String template) {
        return new Rule(code, variant, level, template);
    }

    /** Every rule wording, in catalogue order. */
    public static List<Rule> rules() {
        return RULES;
    }

    /** The level of a rule; every variant of a code has the same one. */
    public static IssueLevel level(String code) {
        return RULES.stream()
                .filter(r -> r.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown rule code " + code))
                .level();
    }

    /** Fills the slots of a wording; {@code params} are key, value pairs. */
    public static String variant(String code, String variant, String... params) {
        Map<String, String> slots = new LinkedHashMap<>();
        for (int i = 0; i + 1 < params.length; i += 2) {
            slots.put(params[i], params[i + 1]);
        }
        return render(code, variant, slots);
    }

    public static String message(String code, String... params) {
        return variant(code, DEFAULT, params);
    }

    public static String render(String code, String variant, Map<String, String> slots) {
        Rule rule = RULES.stream()
                .filter(r -> r.code().equals(code) && r.variant().equals(variant))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown rule " + code + "/" + variant));
        String text = rule.template();
        for (var slot : slots.entrySet()) {
            text = text.replace("{" + slot.getKey() + "}", slot.getValue());
        }
        return text;
    }

    /** A number without trailing zeros: 12.0 becomes "12", 9.5 stays "9.5". */
    public static String number(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return new java.math.BigDecimal(value)
                .setScale(2, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }
}

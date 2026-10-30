package com.djavafactory.castor.core.operations.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Which source is the system of record of a field (ops.source_precedence, README "Decisions: system of record per
 * field"). The ordered list names the sources best first; a source that is not listed ranks below all of them.
 */
public interface SourcePrecedence {

    String VESSEL_CALL = "VESSEL_CALL";

    /** What every port gets until its table says otherwise: the seed of V13. */
    Map<String, List<String>> VESSEL_CALL_DEFAULTS = Map.of(
            "status", List.of("cbas", "edic"),
            "berth", List.of("cbas", "edic"),
            "eta", List.of("cbas", "edic"),
            "etd", List.of("cbas", "edic"),
            "ata", List.of("edic", "cbas"),
            "atd", List.of("edic", "cbas"));

    /** The sources of the field, best first, lower case; empty when the port has no row for it. */
    Optional<List<String>> sources(long portId, String entityType, String field);

    /** The place of the source in the order: 0 is best; an unlisted source gets the size of the list. */
    static int rank(List<String> order, String source) {
        int index = order.indexOf(source == null ? "" : source.toLowerCase());
        return index < 0 ? order.size() : index;
    }
}

package com.djavafactory.castor.gateway.normalize.domain;

import java.util.List;
import java.util.stream.Collectors;

import com.djavafactory.castor.contracts.Violation;

/** The quarantine detail of an event that is not valid: the first violations with their JSON Pointer. */
public final class ViolationText {

    private static final int MAX_VIOLATIONS_REPORTED = 5;

    private ViolationText() {}

    public static String describe(List<Violation> violations) {
        String listed = violations.stream()
                .limit(MAX_VIOLATIONS_REPORTED)
                .map(v -> (v.path().isEmpty() ? "(event)" : v.path()) + ": " + v.message())
                .collect(Collectors.joining("; "));
        int more = violations.size() - MAX_VIOLATIONS_REPORTED;
        return more > 0 ? listed + "; and " + more + " more" : listed;
    }
}

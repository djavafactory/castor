package com.djavafactory.castor.core.layout.domain;

import java.util.List;
import java.util.Objects;

/**
 * One failed check of the rule catalogue (README section 6).
 *
 * @param assetIds the assets the issue is about, the one that is checked first; empty only for layout-wide issues
 */
public record ValidationIssue(String code, IssueLevel level, String message, List<String> assetIds) {

    public ValidationIssue {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(message, "message");
        assetIds = List.copyOf(assetIds);
    }
}

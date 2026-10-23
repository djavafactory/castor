package it.replica8.castor.core.shared.domain;

import java.util.Objects;

/** One failed business rule. {@code assetId} and {@code pointer} may be null when the rule is not tied to a field. */
public record RuleViolation(String code, String assetId, String pointer, String message) {

    public RuleViolation {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}

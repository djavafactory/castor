package com.djavafactory.castor.core.shared.domain;

import java.util.List;

/** A domain rule was broken. Reported as HTTP 422. */
public class RuleViolationException extends RuntimeException {

    private final String title;
    private final List<RuleViolation> violations;

    public RuleViolationException(String title, List<RuleViolation> violations) {
        super(title);
        this.title = title;
        this.violations = List.copyOf(violations);
    }

    public String title() {
        return title;
    }

    public List<RuleViolation> violations() {
        return violations;
    }
}

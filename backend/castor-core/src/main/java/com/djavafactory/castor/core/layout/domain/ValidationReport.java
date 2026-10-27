package com.djavafactory.castor.core.layout.domain;

import java.util.List;

/** The issues of one check, errors first. */
public record ValidationReport(List<ValidationIssue> issues) {

    public ValidationReport {
        issues = List.copyOf(issues);
    }

    public List<ValidationIssue> errors() {
        return of(IssueLevel.ERROR);
    }

    public List<ValidationIssue> warnings() {
        return of(IssueLevel.WARNING);
    }

    public boolean hasErrors() {
        return issues.stream().anyMatch(i -> i.level() == IssueLevel.ERROR);
    }

    private List<ValidationIssue> of(IssueLevel level) {
        return issues.stream().filter(i -> i.level() == level).toList();
    }
}

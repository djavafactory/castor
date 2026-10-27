package com.djavafactory.castor.core.layout.domain;

import java.util.List;
import java.util.UUID;

import com.djavafactory.castor.core.shared.domain.RuleViolation;
import com.djavafactory.castor.core.shared.domain.RuleViolationException;

/** One saved version of a layout. Version numbers start at 1. */
public record LayoutVersion(UUID id, UUID layoutId, int number) {

    public LayoutVersion {
        if (number < 1) {
            throw new RuleViolationException(
                    "Invalid layout version",
                    List.of(new RuleViolation("VERSION_NUMBER", null, "/number", "Version number starts at 1")));
        }
    }
}

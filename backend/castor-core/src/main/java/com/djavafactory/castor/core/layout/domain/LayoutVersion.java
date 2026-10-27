package com.djavafactory.castor.core.layout.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.djavafactory.castor.core.shared.domain.RuleViolation;
import com.djavafactory.castor.core.shared.domain.RuleViolationException;

/**
 * One saved version of the layout of a port. Version numbers start at 1; asset ids are the same in every version.
 *
 * @param rowVersion optimistic lock counter of the version row
 */
public record LayoutVersion(
        UUID id,
        String portCode,
        int versionNo,
        LayoutStatus status,
        String note,
        String createdBy,
        Instant createdAt,
        String publishedBy,
        Instant publishedAt,
        UUID basedOnVersionId,
        long rowVersion) {

    public LayoutVersion {
        if (versionNo < 1) {
            throw new RuleViolationException(
                    "Invalid layout version",
                    List.of(new RuleViolation("VERSION_NUMBER", null, "/versionNo", "Version number starts at 1")));
        }
    }
}

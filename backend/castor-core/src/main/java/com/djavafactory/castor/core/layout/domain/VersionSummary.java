package com.djavafactory.castor.core.layout.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the version list of a port.
 *
 * @param reviewedBy who reviewed the draft before it was published, null when nobody was named
 * @param restoredFromVersionNo number of the version this one was restored from, null when it was not a restore
 */
public record VersionSummary(
        UUID id,
        int versionNo,
        LayoutStatus status,
        String createdBy,
        Instant createdAt,
        String publishedBy,
        Instant publishedAt,
        String reviewedBy,
        Integer restoredFromVersionNo,
        String note,
        int assetCount) {}

package com.djavafactory.castor.core.layout.application;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.core.layout.domain.AssetHistoryEntry;
import com.djavafactory.castor.core.layout.domain.LayoutDiff;
import com.djavafactory.castor.core.layout.domain.LayoutRepository;
import com.djavafactory.castor.core.layout.domain.LayoutStatus;
import com.djavafactory.castor.core.layout.domain.LayoutVersion;
import com.djavafactory.castor.core.layout.domain.VersionSummary;
import com.djavafactory.castor.core.shared.domain.InvalidRequestException;
import com.djavafactory.castor.core.shared.domain.ResourceNotFoundException;

/**
 * Use cases that read history: the versions of a port, the versions of one asset and the difference between two
 * versions. Viewers see published and superseded versions only; editors and above also see drafts and discarded ones.
 */
@ApplicationScoped
public class LayoutHistory {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    private static final Set<LayoutStatus> OPEN = EnumSet.of(LayoutStatus.PUBLISHED, LayoutStatus.SUPERSEDED);

    private final LayoutRepository layouts;

    public LayoutHistory(LayoutRepository layouts) {
        this.layouts = layouts;
    }

    /** @param status one status to keep, or null for every status the caller may see */
    public List<VersionSummary> versions(String portCode, String status, Integer limit, boolean editor) {
        int max = limit == null ? DEFAULT_LIMIT : limit;
        if (max < 1 || max > MAX_LIMIT) {
            throw new InvalidRequestException("limit must be between 1 and " + MAX_LIMIT);
        }
        return layouts.listVersions(portCode.toLowerCase(), visible(status, editor), max);
    }

    public List<AssetHistoryEntry> assetHistory(UUID assetId, boolean editor) {
        if (!layouts.assetExists(assetId)) {
            throw new ResourceNotFoundException("No asset " + assetId);
        }
        return layouts.assetHistory(assetId, visible(null, editor));
    }

    /**
     * What the version has that {@code against} lacks (added), what it lacks (removed) and what changed.
     *
     * @param against the version to compare with; the published version when null
     */
    public LayoutDiff diff(UUID layoutId, UUID against, boolean editor) {
        LayoutVersion version = require(layoutId, editor);
        LayoutVersion other = against == null
                ? layouts.findPublished(version.portCode())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Port '" + version.portCode() + "' has no published layout"))
                : require(against, editor);
        if (!other.portCode().equals(version.portCode())) {
            throw new InvalidRequestException("The two versions belong to different ports");
        }
        return LayoutDiff.compare(
                layouts.load(version).assets(), layouts.load(other).assets());
    }

    private LayoutVersion require(UUID id, boolean editor) {
        return layouts.findVersion(id)
                .filter(v -> editor || OPEN.contains(v.status()))
                .orElseThrow(() -> new ResourceNotFoundException("No layout version " + id));
    }

    private static Set<LayoutStatus> visible(String status, boolean editor) {
        if (status == null || status.isBlank()) {
            return editor ? EnumSet.allOf(LayoutStatus.class) : OPEN;
        }
        LayoutStatus wanted;
        try {
            wanted = LayoutStatus.valueOf(status.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("status must be one of DRAFT, PUBLISHED, SUPERSEDED, DISCARDED");
        }
        if (!editor && !OPEN.contains(wanted)) {
            return Set.of();
        }
        return EnumSet.of(wanted);
    }
}

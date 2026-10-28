package com.djavafactory.castor.core.layout.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** What the layout use cases need from storage. Implemented in infrastructure. */
public interface LayoutRepository {

    /** The newest version of every port. */
    List<LayoutVersion> findLatestVersions();

    /** The published version of a port (lower-case port code). */
    Optional<LayoutVersion> findPublished(String portCode);

    /** The current draft of a port, if there is one. */
    Optional<LayoutVersion> findDraft(String portCode);

    Optional<LayoutVersion> findVersion(UUID layoutId);

    /** Every asset of the version with details and links; measures hold area, perimeter and length only. */
    LayoutView load(LayoutVersion version);

    /** The single asset of a version with its details, links and measures, if the version has it. */
    Optional<LayoutView> loadAsset(LayoutVersion version, UUID assetId);

    /**
     * Creates the DRAFT of a port as the next version number, in the running transaction. Revisions and details are
     * copied from basedOn when it is given; a null basedOn makes a blank draft.
     *
     * @throws com.djavafactory.castor.core.shared.domain.DraftAlreadyExistsException when the port has a draft
     */
    LayoutVersion createDraft(String portCode, LayoutVersion basedOn, String createdBy);

    /** Marks a DRAFT as DISCARDED; its rows are kept. Returns the changed version. */
    LayoutVersion discard(LayoutVersion draft);

    /** The permanent identity of an asset of the port, if the port has it. */
    default Optional<AssetIdentity> findIdentity(String portCode, UUID assetId) {
        throw new UnsupportedOperationException();
    }

    /** Every asset code of the port, whatever version it is used in. */
    default List<String> findCodes(String portCode) {
        throw new UnsupportedOperationException();
    }

    /** The row version of the revision of an asset in a version, if the version has the asset. */
    default Optional<Long> findRowVersion(UUID versionId, UUID assetId) {
        throw new UnsupportedOperationException();
    }

    /**
     * Stores a new asset in the draft, in the running transaction: the identity (created unless assetId is an existing
     * one without a revision here), the revision, details and links. Returns the row version, 0.
     */
    default long insert(LayoutVersion draft, LayoutAsset asset, String actor) {
        throw new UnsupportedOperationException();
    }

    /**
     * Replaces the revision, details and links of an asset of the draft. The stored row version must equal
     * {@code expectedRowVersion}.
     *
     * @return the new row version
     * @throws com.djavafactory.castor.core.shared.domain.ConflictException when the row version differs
     */
    default long update(LayoutVersion draft, LayoutAsset asset, long expectedRowVersion, String actor) {
        throw new UnsupportedOperationException();
    }

    /** Records an override on the revision; same concurrency rule as {@link #update}. Returns the new row version. */
    default long override(
            LayoutVersion draft, UUID assetId, long expectedRowVersion, LayoutAsset.Override override, String actor) {
        throw new UnsupportedOperationException();
    }

    /**
     * Removes the revision (identity kept) with its details and every link to or from it. Returns the codes of the
     * assets that linked to it.
     */
    default List<String> delete(LayoutVersion draft, UUID assetId, long expectedRowVersion) {
        throw new UnsupportedOperationException();
    }

    /** Records that a draft was made by restoring a version. */
    void markRestored(UUID draftId, UUID sourceVersionId);

    /**
     * Turns a DRAFT into the PUBLISHED version of its port in the running transaction: the previous published version
     * becomes SUPERSEDED and the port points at the new one. Returns the published version.
     */
    LayoutVersion publish(LayoutVersion draft, String publishedBy, String note, String reviewedBy);

    /** Versions of a port, newest first; only versions with one of the statuses are listed. */
    List<VersionSummary> listVersions(String portCode, Collection<LayoutStatus> statuses, int limit);

    /** The asset in every version that holds it, newest first. */
    List<AssetHistoryEntry> assetHistory(UUID assetId, Collection<LayoutStatus> statuses);

    /** True when the asset exists. */
    boolean assetExists(UUID assetId);

    /** Number of assets in a version. */
    int countAssets(UUID layoutId);

    /** An asset's identity: permanent id, code and type. */
    record AssetIdentity(UUID id, String code, String typeCode) {}
}

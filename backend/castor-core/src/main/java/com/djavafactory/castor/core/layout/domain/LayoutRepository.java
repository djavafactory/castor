package com.djavafactory.castor.core.layout.domain;

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
}

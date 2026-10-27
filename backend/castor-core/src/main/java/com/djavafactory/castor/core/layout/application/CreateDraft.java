package com.djavafactory.castor.core.layout.application;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import com.djavafactory.castor.core.audit.application.AuditService;
import com.djavafactory.castor.core.audit.domain.AuditAction;
import com.djavafactory.castor.core.layout.domain.LayoutRepository;
import com.djavafactory.castor.core.layout.domain.LayoutStatus;
import com.djavafactory.castor.core.layout.domain.LayoutVersion;
import com.djavafactory.castor.core.shared.domain.DraftAlreadyExistsException;
import com.djavafactory.castor.core.shared.domain.InvalidRequestException;
import com.djavafactory.castor.core.shared.domain.ResourceNotFoundException;

/**
 * Use case: start the one draft of a port from the published layout, an older version or nothing. The draft and its
 * DRAFT_CREATED audit row are written in one transaction.
 */
@ApplicationScoped
public class CreateDraft {

    public static final String PUBLISHED = "published";
    public static final String BLANK = "blank";

    private final LayoutRepository layouts;
    private final AuditService audit;

    public CreateDraft(LayoutRepository layouts, AuditService audit) {
        this.layouts = layouts;
        this.audit = audit;
    }

    /** @param basedOn "published", "blank" or the id of a published or superseded version of the port */
    @Transactional
    public LayoutVersion execute(String portCode, String basedOn, String actor) {
        String port = portCode.toLowerCase();
        if (basedOn == null || basedOn.isBlank()) {
            throw new InvalidRequestException("basedOn is required: \"published\", \"blank\" or a version id");
        }
        var existing = layouts.findDraft(port);
        if (existing.isPresent()) {
            throw new DraftAlreadyExistsException(existing.get().id());
        }
        LayoutVersion source = source(port, basedOn.trim());
        LayoutVersion draft = layouts.createDraft(port, source, actor);
        audit.record(
                AuditAction.DRAFT_CREATED,
                "LAYOUT_VERSION",
                draft.id().toString(),
                draft.id(),
                null,
                LayoutSnapshots.version(draft));
        return draft;
    }

    private LayoutVersion source(String port, String basedOn) {
        if (BLANK.equals(basedOn)) {
            return null;
        }
        if (PUBLISHED.equals(basedOn)) {
            return layouts.findPublished(port)
                    .orElseThrow(() -> new ResourceNotFoundException("Port '" + port + "' has no published layout"));
        }
        UUID id;
        try {
            id = UUID.fromString(basedOn);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("basedOn must be \"published\", \"blank\" or a version id");
        }
        LayoutVersion version = layouts.findVersion(id)
                .filter(v -> v.portCode().equals(port))
                .orElseThrow(() -> new ResourceNotFoundException("No layout version " + id + " in port " + port));
        if (version.status() != LayoutStatus.PUBLISHED && version.status() != LayoutStatus.SUPERSEDED) {
            throw new InvalidRequestException("Only a published or superseded version can be the base of a draft");
        }
        return version;
    }
}

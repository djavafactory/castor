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
 * Use case: bring an older version back as a new draft. Asset ids are the ones of the source version (the copy keeps
 * them), so publishing the draft shows the same assets again. A port has one draft only: an open one is a 409 unless
 * the caller agreed to discard it.
 */
@ApplicationScoped
public class RestoreLayout {

    /** @param draft the new draft; sourceVersionNo is the number of the version it was loaded from */
    public record Result(LayoutVersion draft, int sourceVersionNo) {}

    private final LayoutRepository layouts;
    private final AuditService audit;

    public RestoreLayout(LayoutRepository layouts, AuditService audit) {
        this.layouts = layouts;
        this.audit = audit;
    }

    @Transactional
    public Result execute(UUID versionId, boolean discardDraft, String actor) {
        LayoutVersion source = layouts.findVersion(versionId)
                .orElseThrow(() -> new ResourceNotFoundException("No layout version " + versionId));
        if (source.status() != LayoutStatus.PUBLISHED && source.status() != LayoutStatus.SUPERSEDED) {
            throw new InvalidRequestException("Only a published or superseded version can be restored");
        }
        var existing = layouts.findDraft(source.portCode());
        if (existing.isPresent()) {
            if (!discardDraft) {
                throw new DraftAlreadyExistsException(existing.get().id());
            }
            var discarded = layouts.discard(existing.get());
            audit.record(
                    AuditAction.DRAFT_DISCARDED,
                    "LAYOUT_VERSION",
                    discarded.id().toString(),
                    discarded.id(),
                    LayoutSnapshots.version(existing.get()),
                    LayoutSnapshots.version(discarded));
        }
        LayoutVersion created = layouts.createDraft(source.portCode(), source, actor);
        layouts.markRestored(created.id(), source.id());
        LayoutVersion draft = layouts.findVersion(created.id()).orElseThrow();
        var after = LayoutSnapshots.version(draft);
        after.put("restoredFromVersionId", source.id().toString());
        after.put("restoredFromVersionNo", source.versionNo());
        audit.record(AuditAction.LAYOUT_RESTORED, "LAYOUT_VERSION", draft.id().toString(), draft.id(), null, after);
        return new Result(draft, source.versionNo());
    }
}

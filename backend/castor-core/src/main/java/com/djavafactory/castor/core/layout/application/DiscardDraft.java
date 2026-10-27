package com.djavafactory.castor.core.layout.application;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import com.djavafactory.castor.core.audit.application.AuditService;
import com.djavafactory.castor.core.audit.domain.AuditAction;
import com.djavafactory.castor.core.layout.domain.LayoutRepository;
import com.djavafactory.castor.core.layout.domain.LayoutStatus;
import com.djavafactory.castor.core.shared.domain.ConflictException;
import com.djavafactory.castor.core.shared.domain.ResourceNotFoundException;

/** Use case: throw a draft away. Its rows stay for the audit trail; the port may then start a new draft. */
@ApplicationScoped
public class DiscardDraft {

    private final LayoutRepository layouts;
    private final AuditService audit;

    public DiscardDraft(LayoutRepository layouts, AuditService audit) {
        this.layouts = layouts;
        this.audit = audit;
    }

    @Transactional
    public void execute(UUID layoutId) {
        var draft = layouts.findVersion(layoutId)
                .orElseThrow(() -> new ResourceNotFoundException("No layout version " + layoutId));
        if (draft.status() != LayoutStatus.DRAFT) {
            throw new ConflictException("Only a draft can be discarded");
        }
        var discarded = layouts.discard(draft);
        audit.record(
                AuditAction.DRAFT_DISCARDED,
                "LAYOUT_VERSION",
                layoutId.toString(),
                layoutId,
                LayoutSnapshots.version(draft),
                LayoutSnapshots.version(discarded));
    }
}

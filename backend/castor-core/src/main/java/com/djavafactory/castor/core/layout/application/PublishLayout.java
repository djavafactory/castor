package com.djavafactory.castor.core.layout.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import com.djavafactory.castor.core.audit.application.AuditService;
import com.djavafactory.castor.core.audit.domain.AuditAction;
import com.djavafactory.castor.core.identity.domain.Role;
import com.djavafactory.castor.core.identity.domain.UserRepository;
import com.djavafactory.castor.core.layout.domain.LayoutRepository;
import com.djavafactory.castor.core.layout.domain.LayoutStatus;
import com.djavafactory.castor.core.layout.domain.LayoutVersion;
import com.djavafactory.castor.core.shared.application.OperationMetrics;
import com.djavafactory.castor.core.shared.domain.ConflictException;
import com.djavafactory.castor.core.shared.domain.InvalidRequestException;
import com.djavafactory.castor.core.shared.domain.ResourceNotFoundException;
import com.djavafactory.castor.core.shared.domain.RuleViolation;
import com.djavafactory.castor.core.shared.domain.RuleViolationException;

/**
 * Use case: turn a reviewed draft into the published layout. The same rule catalogue as the Issues list judges the
 * draft; one error refuses the publish and nothing changes. Otherwise the draft becomes PUBLISHED, the previous
 * published version SUPERSEDED and the LAYOUT_PUBLISHED audit row is written, all in one transaction.
 */
@ApplicationScoped
public class PublishLayout {

    public static final int MAX_NOTE = 500;

    /** @param version the published version; assetCount is the number of assets it holds, reviewedBy the reviewer or null */
    public record Result(LayoutVersion version, int assetCount, String reviewedBy) {}

    private final LayoutRepository layouts;
    private final LayoutValidation validation;
    private final UserRepository users;
    private final AuditService audit;
    private final OperationMetrics metrics;

    public PublishLayout(
            LayoutRepository layouts,
            LayoutValidation validation,
            UserRepository users,
            AuditService audit,
            OperationMetrics metrics) {
        this.layouts = layouts;
        this.validation = validation;
        this.users = users;
        this.audit = audit;
        this.metrics = metrics;
    }

    /**
     * @param note what changed, at most 500 characters, may be empty
     * @param reviewedBy username of a publisher or admin who looked at the draft, null or blank when nobody did
     */
    @Transactional
    public Result execute(UUID layoutId, String note, String reviewedBy, String actor) {
        String cleanNote = note == null || note.isBlank() ? null : note.strip();
        if (cleanNote != null && cleanNote.length() > MAX_NOTE) {
            throw new InvalidRequestException("note must be at most " + MAX_NOTE + " characters");
        }
        LayoutVersion draft = layouts.findVersion(layoutId)
                .orElseThrow(() -> new ResourceNotFoundException("No layout version " + layoutId));
        if (draft.status() != LayoutStatus.DRAFT) {
            throw new ConflictException("Only a draft can be published");
        }
        String reviewer = reviewer(reviewedBy, actor);

        var report = validation.layout(layoutId);
        if (report.hasErrors()) {
            List<RuleViolation> violations =
                    report.errors().stream().map(LayoutValidation::violation).toList();
            throw new RuleViolationException(
                    "Publishing blocked: " + violations.size() + " error(s). Fix them first.", violations);
        }

        LayoutVersion published = layouts.publish(draft, actor, cleanNote, reviewer);
        var after = LayoutSnapshots.version(published);
        after.put("reviewedBy", reviewer);
        audit.record(
                AuditAction.LAYOUT_PUBLISHED,
                "LAYOUT_VERSION",
                layoutId.toString(),
                layoutId,
                LayoutSnapshots.version(draft),
                after);
        metrics.layoutPublished();
        return new Result(published, layouts.countAssets(layoutId), reviewer);
    }

    /** The canonical username of the reviewer, or null. A reviewer is a publisher or admin other than the publisher. */
    private String reviewer(String reviewedBy, String actor) {
        if (reviewedBy == null || reviewedBy.isBlank()) {
            return null;
        }
        String name = reviewedBy.strip();
        var user =
                users.findByUsername(name).orElseThrow(() -> rejected("No user '" + name + "' to review this draft"));
        if (user.username().equalsIgnoreCase(actor)) {
            throw rejected("The reviewer must be someone other than the person publishing");
        }
        if (Set.copyOf(user.roles()).stream().noneMatch(r -> r == Role.PUBLISHER || r == Role.ADMIN)) {
            throw rejected("The reviewer must have the PUBLISHER or ADMIN role");
        }
        return user.username();
    }

    private static RuleViolationException rejected(String message) {
        return new RuleViolationException(
                "The reviewer is not accepted",
                List.of(new RuleViolation("REVIEWER_INVALID", null, "/reviewedBy", message)));
    }
}
